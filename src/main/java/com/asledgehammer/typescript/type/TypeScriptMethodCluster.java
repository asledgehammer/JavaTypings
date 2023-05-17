package com.asledgehammer.typescript.type;

import com.asledgehammer.typescript.TypeScriptGraph;
import com.asledgehammer.typescript.settings.TypeScriptSettings;
import com.asledgehammer.typescript.util.ClazzUtils;
import com.asledgehammer.typescript.util.ComplexGenericMap;
import com.asledgehammer.typescript.util.DocBuilder;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import se.krka.kahlua.integration.annotations.LuaMethod;

public class TypeScriptMethodCluster implements TypeScriptWalkable, TypeScriptCompilable {

  public final boolean isStatic;
  public boolean exists = false;
  private final List<List<Parameter>> allParameters = new ArrayList<>();
  private final List<List<String>> allParameterTypes = new ArrayList<>();
  private final List<Method> sortedMethods = new ArrayList<>();
  private final List<String> allReturnTypes = new ArrayList<>();
  private final List<List<Boolean>> canPassNull = new ArrayList<>();
  private final List<List<Boolean>> isVararg = new ArrayList<>();
  private final TypeScriptElement element;
  private final String methodNameOriginal;
  private final String methodName;
  private int minParamCount = Integer.MAX_VALUE;
  private boolean returnTypeContainsNonPrimitive = false;

  public List<Method> getSortedMethods() {
    return sortedMethods;
  }

  public TypeScriptMethodCluster(TypeScriptElement element, Method method) {
    this.element = element;
    this.isStatic = Modifier.isStatic(method.getModifiers());
    this.methodNameOriginal = method.getName();
    // PZ Check for alternatively-exposed method names.
    if (method.isAnnotationPresent(LuaMethod.class)) {
      LuaMethod annotation = method.getAnnotationsByType(LuaMethod.class)[0];
      this.methodName = annotation.name();
    } else {
      this.methodName = method.getName();
    }
  }

  @Override
  public void walk(TypeScriptGraph graph) {
    Class<?> clazz = element.clazz;
    if (clazz == null) {
      return;
    }

    ComplexGenericMap genericMap = this.element.genericMap;
    Method[] ms = clazz.getMethods();

    Collections.addAll(sortedMethods, ms);

    sortedMethods.removeIf(
        method -> !method.getName().equals(TypeScriptMethodCluster.this.methodNameOriginal));

    sortedMethods.sort(
        (o1, o2) -> {

          // Try the original method first. If this is different, then we use this order.
          if (o1.getParameterCount() != o2.getParameterCount()) {
            return o1.getParameterCount() - o2.getParameterCount();
          }

          // Check non-empty method parameters for string comparisons on type class-paths.
          if (o1.getParameterCount() != 0) {
            // If otherwise, we go until the string comparison of type names is not zero.
            Type[] o1Types = o1.getGenericParameterTypes();
            Type[] o2Types = o2.getGenericParameterTypes();
            for (int index = 0; index < o1Types.length; index++) {
              Type o1Type = o1Types[index];
              Type o2Type = o2Types[index];
              int compare = o1Type.getTypeName().compareTo(o2Type.getTypeName());
              if (compare != 0) {
                return compare;
              }
            }
          }

          // Next, check the return type.
          String returnType1 = o1.getGenericReturnType().getTypeName();
          String returnType2 = o2.getGenericReturnType().getTypeName();
          return returnType1.compareTo(returnType2);
        });

    this.exists = sortedMethods.size() != 0;

    this.minParamCount = exists ? Integer.MAX_VALUE : 0;

    for (Method method : sortedMethods) {

      if (!method.getName().equals(this.methodNameOriginal)) {
        continue;
      }
      if (Modifier.isStatic(method.getModifiers()) != isStatic) {
        continue;
      }

      Parameter[] parameters = method.getParameters();
      Type[] types = method.getGenericParameterTypes();
      if (minParamCount > types.length) {
        minParamCount = types.length;
      }

      if (types.length != 0) {
        for (int i = 0; i < types.length; i++) {
          Type argType = types[i];

          List<String> argSlot;
          if (allParameterTypes.size() > i) {
            argSlot = allParameterTypes.get(i);
          } else {
            argSlot = new ArrayList<>();
            allParameterTypes.add(argSlot);
          }

          StringBuilder tName = new StringBuilder(argType.getTypeName());
          if (genericMap != null) {
            tName =
                new StringBuilder(
                    ClazzUtils.walkTypesRecursively(
                        genericMap, method.getDeclaringClass(), tName.toString()));
          }

          tName = new StringBuilder(TypeScriptElement.inspect(graph, tName.toString()));
          tName = new StringBuilder(TypeScriptElement.adaptType(tName.toString()));

          graph.add(parameters[i].getType());

          // Add any missing parameters if not defined.
          if (!tName.toString().contains("<")) {
            TypeVariable<?>[] params = parameters[i].getType().getTypeParameters();
            if (params.length != 0) {
              tName.append("<");
              tName.append("any, ".repeat(params.length));
              tName = new StringBuilder(tName.substring(0, tName.length() - 2) + ">");
            }
          }

          if (!argSlot.contains(tName.toString())) {
            argSlot.add(tName.toString());
          }
        }
      }

      if (parameters.length != 0) {
        for (int i = 0; i < parameters.length; i++) {
          Parameter argParameter = parameters[i];

          List<Parameter> argSlot;
          if (allParameters.size() > i) {
            argSlot = allParameters.get(i);
          } else {
            argSlot = new ArrayList<>();
            allParameters.add(argSlot);
          }

          List<Boolean> nullSlot;
          if (canPassNull.size() > i) {
            nullSlot = canPassNull.get(i);
          } else {
            nullSlot = new ArrayList<>();
            canPassNull.add(nullSlot);
          }

          List<Boolean> varg;
          if (isVararg.size() > i) {
            varg = isVararg.get(i);
          } else {
            varg = new ArrayList<>();
            isVararg.add(varg);
          }

          argSlot.add(argParameter);
          nullSlot.add(argParameter.getType().isPrimitive());
          varg.add(argParameter.isVarArgs());
        }
      }

      for (Parameter parameter : parameters) {
        graph.add(parameter.getType());
      }

      StringBuilder returnType;
      if (genericMap != null) {
        Class<?> declClazz = method.getDeclaringClass();
        String before = method.getGenericReturnType().getTypeName();
        returnType =
            new StringBuilder(ClazzUtils.walkTypesRecursively(genericMap, declClazz, before));
      } else {
        returnType = new StringBuilder(method.getGenericReturnType().getTypeName());
      }

      if (!method.getGenericReturnType().getClass().isPrimitive()) {
        this.returnTypeContainsNonPrimitive = true;
      }

      returnType = new StringBuilder(TypeScriptElement.adaptType(returnType.toString()));
      returnType = new StringBuilder(TypeScriptElement.inspect(graph, returnType.toString()));

      Class<?> returnClazz = method.getReturnType();

      if (!returnType.toString().equals("T") && !returnType.toString().contains("<")) {
        TypeVariable<?>[] params = returnClazz.getTypeParameters();
        if (params.length != 0) {
          returnType.append("<");
          returnType.append("any, ".repeat(params.length));
          returnType = new StringBuilder(returnType.substring(0, returnType.length() - 2) + ">");
        }
      }

      if (returnClazz.equals(Object.class)
          || returnClazz.equals(Object[].class)
          || returnClazz.equals(Object[][].class)
          || returnClazz.equals(Object[][][].class)
          || returnClazz.equals(Object[][][][].class)
          || returnClazz.equals(Object[][][][][].class)
          || returnClazz.equals(Object[][][][][][].class)
          || returnClazz.equals(Object[][][][][][][].class)
          || returnClazz.equals(Object[][][][][][][][].class)
          || returnClazz.equals(Object[][][][][][][][][].class)
          || returnClazz.equals(Object[][][][][][][][][][].class)) {
        returnType = new StringBuilder("any");
      }

      if (!this.allReturnTypes.contains(returnType.toString())) {
        this.allReturnTypes.add(returnType.toString());
      }

      graph.add(returnClazz);
    }
  }

  public String walkDocsLua1(boolean isStatic) {
    Class<?> clazz = element.clazz;
    if (clazz == null || !exists) {
      return "";
    }
    StringBuilder docBuilder = new StringBuilder();
    docBuilder.append("--- @public\n");
    if (isStatic) {
      docBuilder.append("--- @static\n");
    }
    docBuilder.append("---\n--- Method Parameters:\n");

    for (Method method : sortedMethods) {
      if (!methodNameOriginal.equals(method.getName())) {
        continue;
      }
      if (Modifier.isStatic(method.getModifiers()) != isStatic) {
        continue;
      }

      Parameter[] parameters = method.getParameters();

      if (parameters.length != 0) {
        StringBuilder compiled = new StringBuilder("(");
        for (Parameter parameter : method.getParameters()) {
          String tName =
              (parameter.isVarArgs()
                      ? parameter.getType().getComponentType().getSimpleName() + "..."
                      : parameter.getType().getSimpleName())
                  + " "
                  + parameter.getName();
          if (element.genericMap != null) {
            tName = ClazzUtils.walkTypesRecursively(element.genericMap, element.clazz, tName);
          }
          tName = TypeScriptElement.adaptType(tName);
          tName = TypeScriptElement.inspect(element.namespace.getGraph(), tName);
          compiled.append(tName).append(", ");
        }
        compiled = new StringBuilder(compiled.substring(0, compiled.length() - 2) + ')');
        String returnType =
            ClazzUtils.walkTypesRecursively(
                element.genericMap, element.clazz, method.getGenericReturnType().getTypeName());
        returnType = TypeScriptElement.adaptType(returnType);
        returnType = TypeScriptElement.inspect(element.namespace.getGraph(), returnType);
        if (returnType.contains(".")) {
          String[] split = returnType.split("\\.");
          returnType = split[split.length - 1];
        }
        docBuilder.append("--- - ").append(compiled).append(": ").append(returnType).append("\n");
      } else {
        String compiled = "(Empty)";
        String returnType =
            ClazzUtils.walkTypesRecursively(
                element.genericMap, element.clazz, method.getGenericReturnType().getTypeName());
        returnType = TypeScriptElement.adaptType(returnType);
        returnType = TypeScriptElement.inspect(element.namespace.getGraph(), returnType);
        if (returnType.contains(".")) {
          String[] split = returnType.split("\\.");
          returnType = split[split.length - 1];
        }
        docBuilder.append("--- - ").append(compiled).append(": ").append(returnType).append("\n");
      }
    }

    int argLength = allParameterTypes.size();
    if (argLength != 0) {
      docBuilder.append("---\n");

      for (int index = 0; index < argLength; index++) {
        docBuilder.append("--- @param arg").append(index + 1).append(" ");

        List<Parameter> params = allParameters.get(index);
        StringBuilder s = new StringBuilder();

        for (Parameter next : params) {
          String n = next.getType().getSimpleName();
          if (s.indexOf(" " + n + " ") != -1) {
            continue;
          }
          if (s.length() != 0) {
            s.append(" | ");
          }
          s.append(n);
        }
        docBuilder.append(s).append('\n');
      }
    }

    return docBuilder.toString();
  }

  public String walkDocsLua2(boolean isStatic) {
    Class<?> clazz = element.clazz;
    if (clazz == null || !exists) {
      return "";
    }
    StringBuilder docBuilder = new StringBuilder();
    docBuilder.append("--- @public\n");
    if (isStatic) {
      docBuilder.append("--- @static\n");
    }

    if (sortedMethods.size() > 1) {
      docBuilder.append("---\n--- Additional Methods:\n");
      for (int i = 1; i < sortedMethods.size(); i++) {
        Method method = sortedMethods.get(i);
        if (!methodNameOriginal.equals(method.getName())) {
          continue;
        }
        if (Modifier.isStatic(method.getModifiers()) != isStatic) {
          continue;
        }

        Parameter[] parameters = method.getParameters();
        docBuilder.append("--- @overload fun(");
        if (parameters.length != 0) {
          StringBuilder compiled = new StringBuilder();
          for (Parameter parameter : method.getParameters()) {
            Class<?> pType = parameter.getType();
            String tType =
                    (parameter.isVarArgs()
                            ? pType.getComponentType().getSimpleName() + "..."
                            : pType.getSimpleName());
            String tName = parameter.getName();
            tName = TypeScriptElement.adaptType(tName);
            tName = TypeScriptElement.inspect(element.namespace.getGraph(), tName);
            compiled.append(tName).append(": ").append(tType).append(", ");
          }
          compiled = new StringBuilder(compiled.substring(0, compiled.length() - 2) + ')');
          docBuilder.append(compiled).append("\n");
        } else {
          String compiled = "(Empty)";

          docBuilder.append(compiled).append("\n\n");
        }
      }
      docBuilder.append("---\n");
    }

    Method methodFirst = sortedMethods.get(0);
    Parameter[] parameters = methodFirst.getParameters();
    if (parameters.length != 0) {
      docBuilder.append("---\n--- Parameters:\n");
      for (Parameter parameter : parameters) {
        String tName = parameter.getName();
        tName = TypeScriptElement.adaptType(tName);
        tName = TypeScriptElement.inspect(element.namespace.getGraph(), tName);
        docBuilder
            .append("--- @param ")
            .append(tName)
            .append(" ")
            .append(parameter.getType().getSimpleName())
            .append('\n');
      }
    }

    docBuilder.append("---\n--- @return ").append(methodFirst.getReturnType().getSimpleName()).append("\n");

    return docBuilder.toString();
  }

  private String walkDocs(String prefix) {
    Class<?> clazz = element.clazz;
    if (clazz == null || !exists) {
      return "";
    }
    DocBuilder docBuilder = new DocBuilder();
    if (isStatic) {
      docBuilder.appendLine("@noSelf");
      docBuilder.appendLine();
    }
    docBuilder.appendLine("Method Parameters: ");

    for (Method method : sortedMethods) {
      if (!methodNameOriginal.equals(method.getName())) {
        continue;
      }
      if (Modifier.isStatic(method.getModifiers()) != isStatic) {
        continue;
      }

      Parameter[] parameters = method.getParameters();

      if (parameters.length != 0) {
        StringBuilder compiled = new StringBuilder("(");
        for (Parameter parameter : method.getParameters()) {
          String tName =
              (parameter.isVarArgs()
                      ? parameter.getType().getComponentType().getSimpleName() + "..."
                      : parameter.getType().getSimpleName())
                  + " "
                  + parameter.getName();
          if (element.genericMap != null) {
            tName = ClazzUtils.walkTypesRecursively(element.genericMap, element.clazz, tName);
          }
          tName = TypeScriptElement.adaptType(tName);
          tName = TypeScriptElement.inspect(element.namespace.getGraph(), tName);
          compiled.append(tName).append(", ");
        }
        compiled = new StringBuilder(compiled.substring(0, compiled.length() - 2) + ')');
        String returnType =
            ClazzUtils.walkTypesRecursively(
                element.genericMap, element.clazz, method.getGenericReturnType().getTypeName());
        returnType = TypeScriptElement.adaptType(returnType);
        returnType = TypeScriptElement.inspect(element.namespace.getGraph(), returnType);
        docBuilder.appendLine(" - " + compiled + ": " + returnType);
      } else {
        String compiled = "(Empty)";
        String returnType =
            ClazzUtils.walkTypesRecursively(
                element.genericMap, element.clazz, method.getGenericReturnType().getTypeName());
        returnType = TypeScriptElement.adaptType(returnType);
        returnType = TypeScriptElement.inspect(element.namespace.getGraph(), returnType);
        docBuilder.appendLine(" - " + compiled + ": " + returnType);
      }
    }

    return docBuilder.build(prefix);
  }

  @Override
  public String compile(String prefix) {
    TypeScriptSettings settings = element.getNamespace().getGraph().getCompiler().getSettings();

    StringBuilder builder = new StringBuilder();
    builder.append(walkDocs(prefix)).append('\n');
    builder.append(prefix);

    if (isStatic) {
      builder.append("static ");
    }
    builder.append(sanitizeName(methodName));

    StringBuilder genericParamsBody;
    List<String> genericTypeNames = new ArrayList<>();
    List<TypeVariable<?>> genericTypes = new ArrayList<>();
    for (Method m : sortedMethods) {
      TypeVariable<?>[] tvs = m.getTypeParameters();
      if (tvs.length != 0) {
        for (TypeVariable<?> tv : tvs) {
          if (genericTypeNames.contains(tv.getTypeName())) {
            continue;
          }
          genericTypeNames.add(tv.getTypeName());
          genericTypes.add(tv);
        }
      }
    }

    if (genericTypes.size() != 0) {
      genericParamsBody = new StringBuilder("<");
      for (TypeVariable<?> variable : genericTypes) {
        genericParamsBody.append(variable.getTypeName()).append(", ");
      }
      genericParamsBody =
          new StringBuilder(genericParamsBody.substring(0, genericParamsBody.length() - 2) + '>');
      builder.append(genericParamsBody);
    }

    builder.append('(');
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < allParameterTypes.size(); i++) {

      String sEntry = "arg" + i;
      if (i > minParamCount - 1) {
        sEntry += '?';
      }
      sEntry += ": ";

      StringBuilder sParams = new StringBuilder();
      List<Parameter> params = allParameters.get(i);
      List<String> argSlot = allParameterTypes.get(i);
      ComplexGenericMap genericMap = element.genericMap;

      boolean hasAny = false;
      for (String argSlotEntry : argSlot) {
        if (argSlotEntry.equals("any")) {
          hasAny = true;
          break;
        }
      }

      if (hasAny) {
        s.append(sEntry).append("any");
      } else {
        for (int j = 0; j < argSlot.size(); j++) {
          String argSlotEntry = argSlot.get(j);
          Parameter parameter = params.get(j);
          Class<?> methodClass = parameter.getDeclaringExecutable().getDeclaringClass();
          String transformedArg;
          if (parameter.getType().equals(Object.class)
              || parameter.getType().equals(Object[].class)
              || parameter.getType().equals(Object[][].class)
              || parameter.getType().equals(Object[][][].class)
              || parameter.getType().equals(Object[][][][].class)
              || parameter.getType().equals(Object[][][][][].class)
              || parameter.getType().equals(Object[][][][][][].class)
              || parameter.getType().equals(Object[][][][][][][].class)
              || parameter.getType().equals(Object[][][][][][][][].class)
              || parameter.getType().equals(Object[][][][][][][][][].class)
              || parameter.getType().equals(Object[][][][][][][][][][].class)) {
            transformedArg = "any";
          } else {
            transformedArg = ClazzUtils.walkTypesRecursively(genericMap, methodClass, argSlotEntry);
          }
          sParams.append(transformedArg).append(" | ");
        }

        s.append(sEntry).append(sParams.substring(0, sParams.length() - 3));
      }

      if (settings.useNull) {
        List<Boolean> paramPrimitiveList = canPassNull.get(i);
        for (Boolean aBoolean : paramPrimitiveList) {
          if (aBoolean) {
            s.append(" | null");
            break;
          }
        }
      }
      s.append(", ");
    }

    if (s.length() != 0) {
      s = new StringBuilder(s.substring(0, s.length() - 2));
    }
    builder.append(s).append("): ");

    StringBuilder returned = new StringBuilder();
    boolean hasAny = false;
    for (String returnType : allReturnTypes) {
      if (returnType.equals("any")) {
        hasAny = true;
        returned = new StringBuilder("any");
        break;
      }
    }

    if (!hasAny) {
      for (String returnType : allReturnTypes) {
        if (returned.length() > 0) {
          returned.append(" | ");
        }
        returned.append(returnType);
      }
    }

    if (settings.useNull && returnTypeContainsNonPrimitive) {
      if (returned.length() > 0) {
        returned.append(" | ");
      }
      returned.append("null");
    }

    builder.append(returned).append(';');
    return builder.toString();
  }

  public String compileLuaParams() {
    StringBuilder params = new StringBuilder();
    Method methodFirst = sortedMethods.get(0);
    Parameter[] parameters = methodFirst.getParameters();
    if (parameters.length != 0) {
      for (Parameter parameter : parameters) {
        params.append(parameter.getName()).append(", ");
      }
      params = new StringBuilder(params.substring(0, params.length() - 2));
    }
    return params.toString();
  }

  public String compileLua(String table) {
    String p = '(' + compileLuaParams() + ')';
    String compiled = "function " + table + '.' + sanitizeName(methodName) + p;
    compiled += " return " + this.methodName + p + " end";
    return compiled;
  }

  public String compileTypeScriptFunction(String prefix) {
    TypeScriptSettings settings = element.getNamespace().getGraph().getCompiler().getSettings();
    StringBuilder builder = new StringBuilder();
    builder.append(walkDocs(prefix)).append('\n');
    builder.append(prefix).append("export function ").append(sanitizeName(methodName));

    StringBuilder genericParamsBody;
    List<String> genericTypeNames = new ArrayList<>();
    List<TypeVariable<?>> genericTypes = new ArrayList<>();
    for (Method m : sortedMethods) {
      TypeVariable<?>[] tvs = m.getTypeParameters();
      if (tvs.length != 0) {
        for (TypeVariable<?> tv : tvs) {
          if (genericTypeNames.contains(tv.getTypeName())) {
            continue;
          }
          genericTypeNames.add(tv.getTypeName());
          genericTypes.add(tv);
        }
      }
    }

    if (genericTypes.size() != 0) {
      genericParamsBody = new StringBuilder("<");
      for (TypeVariable<?> variable : genericTypes) {
        genericParamsBody.append(variable.getTypeName()).append(", ");
      }
      genericParamsBody =
          new StringBuilder(genericParamsBody.substring(0, genericParamsBody.length() - 2) + '>');
      builder.append(genericParamsBody);
    }

    builder.append('(');
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < allParameterTypes.size(); i++) {

      String sEntry = "arg" + i;
      if (i > minParamCount - 1) {
        sEntry += '?';
      }
      sEntry += ": ";

      StringBuilder sParams = new StringBuilder();
      List<Parameter> params = allParameters.get(i);
      List<String> argSlot = allParameterTypes.get(i);
      for (int j = 0; j < argSlot.size(); j++) {
        String argSlotEntry = argSlot.get(j);
        sParams
            .append(
                ClazzUtils.walkTypesRecursively(
                    element.genericMap,
                    params.get(j).getDeclaringExecutable().getDeclaringClass(),
                    argSlotEntry))
            .append(" | ");
      }

      s.append(sEntry).append(sParams.substring(0, sParams.length() - 3));

      boolean isPrimitive = false;
      List<Boolean> paramPrimitiveList = canPassNull.get(i);
      for (Boolean aBoolean : paramPrimitiveList) {
        if (aBoolean) {
          isPrimitive = true;
          break;
        }
      }
      if (settings.useNull && !isPrimitive) {
        s.append(" | null");
      }

      s.append(", ");
    }

    if (s.length() != 0) {
      s = new StringBuilder(s.substring(0, s.length() - 2));
    }

    builder.append(s).append("): ");

    StringBuilder returned = new StringBuilder();
    for (String returnType : allReturnTypes) {
      if (returned.length() > 0) {
        returned.append(" | ");
      }
      returned.append(returnType);
    }

    if (settings.useNull && returnTypeContainsNonPrimitive && returned.length() > 0) {
      returned.append(" | null");
    }

    builder.append(returned).append(';');
    return builder.toString();
  }

  private static String sanitizeName(String name) {
    if (name.equals("instanceof")) {
      return '_' + name + "_";
    }
    return name;
  }

  private static String unSanitizeName(String name) {
    if (name.startsWith("_") && name.endsWith("_")) {
      return name.substring(1, name.length() - 1);
    }
    return name;
  }
}
