# cats-effect-utils

[![Test Workflow](https://github.com/LolHens/cats-effect-utils/workflows/test/badge.svg)](https://github.com/LolHens/cats-effect-utils/actions?query=workflow%3Atest)
[![Release Notes](https://img.shields.io/github/release/LolHens/cats-effect-utils.svg?maxAge=3600)](https://github.com/LolHens/cats-effect-utils/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/de.lolhens/cats-effect-utils_2.13)](https://search.maven.org/artifact/de.lolhens/cats-effect-utils_2.13)
[![Apache License 2.0](https://img.shields.io/github/license/LolHens/cats-effect-utils.svg?maxAge=3600)](https://www.apache.org/licenses/LICENSE-2.0)

This project provides several utility methods for [cats-effect](https://github.com/typelevel/cats-effect).

## Usage

### build.sbt

```sbt
// use this snippet for cats-effect 3 and the JVM
libraryDependencies += "de.lolhens" %% "cats-effect-utils" % "0.3.0"

// use this snippet for cats-effect 3 and JS, or cross-building
libraryDependencies += "de.lolhens" %%% "cats-effect-utils" % "0.3.0"

// use this snippet for cats-effect 2 and the JVM
libraryDependencies += "de.lolhens" %% "cats-effect-utils" % "0.0.1"

// use this snippet for cats-effect 2 and JS, or cross-building
libraryDependencies += "de.lolhens" %%% "cats-effect-utils" % "0.0.1"
```

## License

This project uses the Apache 2.0 License. See the file called LICENSE.
