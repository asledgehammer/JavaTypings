import { execSync } from "child_process"
import fg from "fast-glob"
import {
  copyFileSync,
  ensureDirSync,
  removeSync,
} from "fs-extra"
import path from "path"

// const pzDir = `F:/SteamLibrary/steamapps/common/ProjectZomboid`
const pzDir = ``
const outDir = "lib"
const classOutDir = "zombie_classes"

const sourceDir = path.normalize(pzDir)
const destDir = path.join(process.cwd(), outDir)

if (!pzDir) {
    console.error(`Please set Project Zomboid dir!`)
    return
}

const collectClassesFiles = (
  sourceDir: string,
  destDir: string
) => {
  console.log(`Copying *.class`)
  const javaClassFiles = fg.sync("**/*.class", {
    onlyFiles: true,
    cwd: sourceDir,
    absolute: false,
  })
  removeSync(destDir)
  ensureDirSync(destDir)
  javaClassFiles.forEach((javaClassFile) => {
    const sourceFilePath = path.join(
      sourceDir,
      javaClassFile
    )
    const destFilePath = path.join(destDir, javaClassFile)
    const targetDir = path.dirname(destFilePath)
    ensureDirSync(targetDir)
    copyFileSync(sourceFilePath, destFilePath)
  })
}

const collectOtherFiles = (
  sourceDir: string,
  destDir: string
) => {
  const needCopyFiles = [
    // `stdlib.lbc` needs to be in the jar file
    {
      from: "stdlib.lbc",
      to: "stdlib.lbc",
    },
    {
      from: "serialize.lua",
      to: "serialize.lua",
      toRoot: true,
    },
    {
      from: "media/lua/shared/defines.lua",
      to: "media/lua/shared/defines.lua",
      toRoot: true,
    },
    {
      from: "media/lua/shared/Sandbox/Apocalypse.lua",
      to: "media/lua/shared/Sandbox/Apocalypse.lua",
      toRoot: true,
    },
  ]
  needCopyFiles.forEach((fileInfo) => {
    if (fileInfo.toRoot) {
      const dest = path.join(process.cwd(), fileInfo.to)
      ensureDirSync(path.dirname(dest))
      copyFileSync(
        path.join(sourceDir, fileInfo.from),
        dest
      )
    } else {
      const dest = path.join(destDir, fileInfo.to)
      ensureDirSync(path.dirname(dest))
      copyFileSync(
        path.join(sourceDir, fileInfo.from),
        dest
      )
    }
  })
}

const packFilesIntoJar = (
  dir: string,
  outDir: string,
  version: string = "42"
) => {
  const jarName = `b${version}.jar`
  console.log(`Package the file into the ${jarName}`)
  const jarCmd = `jar cvf ${outDir}/${jarName} -C ${outDir}/${dir}/ .`

  execSync(jarCmd, {
    stdio: "ignore",
  })
}

const packPzJar = ({
  sourceDir,
  destDir,
  classOutDir,
  outDir,
}: {
  sourceDir: string
  destDir: string
  outDir: string
  classOutDir: string
}) => {
  const classOutTargetDir = path.join(destDir, classOutDir)
  const version = "42"
  console.log(`Start packaging b${version}.jar`)

  removeSync(destDir)
  collectClassesFiles(sourceDir, classOutTargetDir)
  collectOtherFiles(sourceDir, classOutTargetDir)
  packFilesIntoJar(classOutDir, outDir)

  console.log(`Removing temporary files`)
  removeSync(classOutTargetDir)

  console.log(`Complete b${version}.ja packaging`)
}

const collectJarFiles = ({
  sourceDir,
  destDir,
}: {
  sourceDir: string
  destDir: string
}) => {
  console.log(`Copying jar files`)
  const javaJarFiles = fg.sync(
    ["**/*.jar", "!jre/**/*.jar", "!jre64/**/*.jar"],
    {
      onlyFiles: true,
      cwd: sourceDir,
      absolute: false,
    }
  )

  javaJarFiles.forEach((javaJarFile) => {
    const sourceFilePath = path.join(sourceDir, javaJarFile)
    const destFilePath = path.join(destDir, javaJarFile)
    const targetDir = path.dirname(destFilePath)
    ensureDirSync(targetDir)
    copyFileSync(sourceFilePath, destFilePath)
  })
}

const collectNeededFiles = ({
  sourceDir,
  destDir,
  outDir,
  classOutDir,
}: {
  sourceDir: string
  destDir: string
  outDir: string
  classOutDir: string
}) => {
  packPzJar({
    sourceDir,
    destDir,
    outDir,
    classOutDir,
  })
  collectJarFiles({
    sourceDir,
    destDir,
  })
}

const calcUsedTime = async (cb: () => void) => {
  const start = Date.now()
  await cb()
  console.log(`Used time: ${Date.now() - start}ms`)
}

calcUsedTime(() => {
  collectNeededFiles({
    sourceDir,
    destDir,
    outDir,
    classOutDir,
  })
})
