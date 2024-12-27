# Project Zomboid Java API Typescript Definition Generator

## Run instructions

### MacOS

#### Prerequisites

- steamcmd : `brew install --cask steamcmd`
- javasdk : `brew install java`
- gradle : `brew install gradle`
- Node.js v20+: `brew install node`

#### Run

##### Install Project Zomboid
Download pz dedicated server to a local directory using steamcd(or skip this step and use the local pz files)

```shell
bash scripts/install_pz.sh
```

##### Generate project libs
Install nodejs project dependencies
```shell
npm install
```

set pz directory in `src-ts/extractLib.ts`
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
