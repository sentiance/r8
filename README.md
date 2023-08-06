This repo is a fork of [Google's R8 compiler](https://r8.googlesource.com/r8) for Android, with the following
modifications:

#### Support '-applymappingonly' option (v8.1.54-sentiance.1)

This option is similar to the existing `-applymapping` option, but restricts the name minification to the classes and
members that are mentioned in the accompanying naming seed file (mapping file). Therefore, when minifying classes and
members, if the class or member is not found in the seed file, no change will be made to its name.
