# Project Zomboid Java API Typescript Definition Generator

## Run instructions

### MacOS

#### Prerequisites

- steamcmd : `brew install --cask steamcmd`
- javasdk : `brew install java`
- gradle : `brew install gradle`
- nodejs v20+: `brew install node`

#### Run

##### Install Project Zomboid
Download pz dedicated server to a local directory using steamcd(or skip this step and use the local pz files)

```shell
bash scripts/install_pz.sh
```

Grab the compiled java byte-code files + jar files from the dedicated server distribution
and throw them into a jar file so we can use them as dependencies

```shell
bash scripts/prep_libs.sh
```

##### Generate project libs
Install nodejs project dependencies
```shell
npm install
```

set pz dedicated server directory or local pz directory in `src-ts/extractLib.ts`
```typescript
// for example
// const pzDir = `F:/SteamLibrary/steamapps/common/ProjectZomboid`
const pzDir = `<Your ProjectZomboid directory>`
```

Run `exec` command
```shell
npm run exec
```

##### Generate java types
Now you can run the generator and create those type defs!

```shell
gradle run --args ./dist
```
