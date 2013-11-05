# pginstrument

pginstrument provides a javaagent that you can use to generate a [ProGuard]
(http://proguard.sourceforge.net/) configuration based on classes and methods
referenced by a running application.

## Quick Start

Add pginstrument to your JVM options upon running:

`-javaagent:pginstrument-0.1.0-shadow.jar -Xbootclasspath/a:pginstrument-0.1.0-shadow.jar`

Upon existing from your application, pginstrument will produce a file called
`config.pro`.  You can use the `keep` statements from this file in your
ProGuard configuration to make sure you didn't miss anything.