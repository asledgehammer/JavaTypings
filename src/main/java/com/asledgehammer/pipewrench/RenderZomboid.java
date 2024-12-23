package com.asledgehammer.pipewrench;

import com.asledgehammer.typescript.TypeScriptCompiler;
import com.asledgehammer.typescript.settings.Recursion;
import com.asledgehammer.typescript.settings.TypeScriptSettings;
import com.asledgehammer.typescript.type.*;
import zombie.Lua.LuaManager;
import zombie.ZomboidFileSystem;
import zombie.core.random.RandStandard;

import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

@SuppressWarnings({"ResultOfMethodCallIgnored", "SpellCheckingInspection", "unused"})
public class RenderZomboid {

  private final File outDir;
  private final File javaDir;

  public RenderZomboid(String outDir) {
    this.outDir = new File(outDir);
    this.outDir.mkdirs();
    this.javaDir = new File(this.outDir, "java");
    this.javaDir.mkdirs();
    File luaDir = new File(this.outDir, "lua");
    luaDir.mkdirs();
  }

  private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
  private static final List<Class<?>> classes = new ArrayList<>();
  private static final TypeScriptCompiler tsCompiler;

  static {
    addClassesToRender();

    TypeScriptSettings tsSettings = new TypeScriptSettings();
    tsSettings.methodsBlackListByPath.add("java.lang.Object#equals");
    tsSettings.methodsBlackListByPath.add("java.lang.Object#getClass");
    tsSettings.methodsBlackListByPath.add("java.lang.Object#hashCode");
    tsSettings.methodsBlackListByPath.add("java.lang.Object#notify");
    tsSettings.methodsBlackListByPath.add("java.lang.Object#notifyAll");
    tsSettings.methodsBlackListByPath.add("java.lang.Object#toString");
    tsSettings.methodsBlackListByPath.add("java.lang.Object#wait");
    tsSettings.recursion = Recursion.NONE;
    tsSettings.readOnly = true;

    tsCompiler = new TypeScriptCompiler(tsSettings);
    for (Class<?> clazz : classes) {
      tsCompiler.add(clazz);
    }
  }

  public static String MODULE_NAME = "@asledgehammer/pipewrench";

  public void render() {
    tsCompiler.walk();
    renderZomboidAsMultiFile();
    renderLuaZomboid();
  }

  private void renderZomboidAsMultiFile() {

    Map<TypeScriptNamespace, String> compiledNamespaces =
        tsCompiler.compileNamespacesSeparately("  ");

    // Write all references to a file to refer to for all files.
    List<String> references = new ArrayList<>();
    for (TypeScriptNamespace namespace : compiledNamespaces.keySet()) {
      String fileName = namespace.getFullPath().replaceAll("\\.", "_") + ".d.ts";
      references.add("/// <reference path=\"java/" + fileName + "\" />\n");
    }

    references.sort(Comparator.naturalOrder());

    StringBuilder referenceBuilder = new StringBuilder();
    for (String s : references) {
      referenceBuilder.append(s);
    }

    String output = "// [PARTIAL:START]\n";
    output += referenceBuilder;
    output += "// [PARTIAL:STOP]\n";
    write(new File(outDir, "java.reference.partial.d.ts"), output);

    for (TypeScriptNamespace namespace : compiledNamespaces.keySet()) {
      output = "/** @noSelfInFile */\n";
      output += "declare module '" + MODULE_NAME + "' {\n";
      output += compiledNamespaces.get(namespace) + "\n";

      List<TypeScriptElement> elements = namespace.getAllGeneratedElements();
      List<String> knownNames = new ArrayList<>();
      List<TypeScriptElement> prunedElements = new ArrayList<>();
      for (int index = elements.size() - 1; index >= 0; index--) {
        TypeScriptElement element = elements.get(index);
        Class<?> clazz = element.getClazz();
        if (clazz == null) {
          continue;
        }
        String name = clazz.getSimpleName();
        if (name.contains("$")) {
          String[] split = name.split("\\$");
          name = split[split.length - 1];
        }
        if (!knownNames.contains(name)) {
          prunedElements.add(element);
          knownNames.add(name);
        }
      }
      prunedElements.sort(nameSorter);

      output += "}\n";

      String fileName = namespace.getFullPath().replaceAll("\\.", "_") + ".d.ts";
      System.out.println("Writing file: " + fileName + "..");
      write(new File(javaDir, fileName), output);
    }

    String prepend = "/** @noSelfInFile */\n";
    prepend += "/// <reference path=\"java.reference.partial.d.ts\" />\n";
    prepend += "declare module '" + MODULE_NAME + "' {\n";
    prepend += "  // [PARTIAL:START]\n";
    TypeScriptClass globalObject =
        (TypeScriptClass) tsCompiler.resolve(LuaManager.GlobalObject.class);

    List<TypeScriptElement> elements = tsCompiler.getAllGeneratedElements();
    List<String> knownNames = new ArrayList<>();
    List<TypeScriptElement> prunedElements = new ArrayList<>();

    for (int index = elements.size() - 1; index >= 0; index--) {
      TypeScriptElement element = elements.get(index);
      Class<?> clazz = element.getClazz();
      if (clazz == null) {
        continue;
      }
      String name = clazz.getSimpleName();
      if (name.contains("$")) {
        String[] split = name.split("\\$");
        name = split[split.length - 1];
      }
      if (!knownNames.contains(name)) {
        prunedElements.add(element);
        knownNames.add(name);
      }
    }

    prunedElements.sort(nameSorter);

    StringBuilder builderTypes = new StringBuilder();
    StringBuilder builderClasses = new StringBuilder();
    StringBuilder builderMethods = new StringBuilder();
    for (TypeScriptElement element : prunedElements) {

      String name = element.getClazz().getSimpleName();
      if (name.contains("$")) {
        String[] split = name.split("\\$");
        name = split[split.length - 1];
      }

      int genParams = element.getClazz().getTypeParameters().length;
      StringBuilder params = new StringBuilder();
      if (genParams != 0) {
        params.append("<");
        for (int x = 0; x < genParams; x++) {
          if (x == 0) {
            params.append("any");
          } else {
            params.append(", any");
          }
        }
        params.append(">");
      }

      String s;
      if (element instanceof TypeScriptType) {
        String fullPath = element.getClazz().getName();
        fullPath = fullPath.replaceAll(".function.", "._function_.");
        s = "  export type " + name + " = " + fullPath + params + '\n';
        if (builderTypes.indexOf(s, 0) == -1) {
          builderTypes.append(s);
        }
      } else {
        s = "  /** @customConstructor " + name + ".new */\n";
        s +=
            "  export class "
                + name
                + " extends "
                + element.getClazz().getName()
                + params
                + " {}\n";
        if (builderClasses.indexOf(s, 0) == -1) {
          builderClasses.append(s);
        }
      }
    }

    Map<String, TypeScriptMethodCluster> methods = globalObject.getStaticMethods();
    List<String> methodNames = new ArrayList<>(methods.keySet());
    methodNames.sort(Comparator.naturalOrder());

    for (String methodName : methodNames) {
      TypeScriptMethodCluster method = methods.get(methodName);
      String s = method.compileTypeScriptFunction("  ") + '\n';
      builderMethods.append(s);
    }

    // Add these two methods to the API. This helps arbitrate EventListener handling
    // for custom solutions / APIs.
    builderMethods.append("  export function addEventListener(id: string, listener: any): void;\n");
    builderMethods.append(
        "  export function removeEventListener(id: string, listener: any): void;\n");

    File fileZomboid = new File(outDir, "java.api.partial.d.ts");
    String content = prepend + builderClasses + '\n' + builderTypes + '\n' + builderMethods;
    content += "// [PARTIAL:STOP]\n";
    content += "}\n";
    System.out.println("Writing file: java.api.partial.d.ts..");
    write(fileZomboid, content);
  }

  private void renderLuaZomboid() {

    List<TypeScriptElement> elements = tsCompiler.getAllGeneratedElements();
    elements.sort(nameSorter);

    String s =
        """
        local Exports = {}
        -- [PARTIAL:START]
        function Exports.tonumber(arg) return tonumber(arg) end
        function Exports.tostring(arg) return tostring(arg) end
        function Exports.global(id) return _G[id] end
        function Exports.loadstring(lua) return loadstring(lua) end
        function Exports.execute(lua) return loadstring(lua)() end
        function Exports.addEventListener(id, func) Events[id].Add(func) end
        function Exports.removeEventListener(id, func) Events[id].Remove(func) end
        """;

    StringBuilder builder = new StringBuilder(s);
    builder.append(tsCompiler.resolve(LuaManager.GlobalObject.class).compileLua("Exports"));

    for (TypeScriptElement element : elements) {
      if (element instanceof TypeScriptClass || element instanceof TypeScriptEnum) {
        String name = element.name;
        if (name.contains("$")) {
          String[] split = name.split("\\$");
          name = split[split.length - 1];
        }
        String line = "Exports." + name + " = loadstring(\"return _G['" + name + "']\")()\n";
        builder.append(line);
      }
    }
    builder.append("-- [PARTIAL:STOP]\n");
    builder.append("return Exports\n");

    // Here we have to name the Lua file exactly the same as the module so require
    // statements work.
    File fileZomboidLua = new File(this.outDir, "java.interface.partial.lua");
    write(fileZomboidLua, builder.toString());
  }

  static void addClassesToRender() {
    addClassesFrom42();

    classes.sort(Comparator.comparing(Class::getSimpleName));
  }

  private static void addClassesFrom42() {
    try {
      RandStandard.INSTANCE.init();
      ZomboidFileSystem.instance.init();

      LuaManager.init();

      LuaManager.Exposer exposer = LuaManager.exposer;
      Class<? extends LuaManager.Exposer> exposerClass = exposer.getClass();

      var exposed = exposerClass.getDeclaredField("exposed");
      exposed.setAccessible(true);

      //noinspection unchecked
      var exposedHashSet = (HashSet<? extends Class<?>>) exposed.get(exposer);
      for (var cls: exposedHashSet) {
        addClass(cls);
      }
    } catch (Exception e) {
        //noinspection CallToPrintStackTrace
        e.printStackTrace();
    }
  }

  private static void addClass(Class<?> clazz) {
    if (classes.contains(clazz)) return;
    classes.add(clazz);
  }

  private static void write(File file, String content) {
    try {
      FileWriter writer = new FileWriter(file);
      writer.write(content);
      writer.flush();
      writer.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static final Comparator<TypeScriptElement> nameSorter =
      (o1, o2) -> {
        String name1 = o1.getClazz() != null ? o1.getClazz().getSimpleName() : o1.getName();
        if (name1.contains("$")) {
          String[] split = name1.split("\\$");
          name1 = split[split.length - 1];
        }
        String name2 = o2.getClazz() != null ? o2.getClazz().getSimpleName() : o2.getName();
        if (name2.contains("$")) {
          String[] split = name2.split("\\$");
          name2 = split[split.length - 1];
        }
        return name1.compareTo(name2);
      };
}
