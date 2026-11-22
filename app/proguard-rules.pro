# R8 rules for the release build (app/build.gradle.kts). The libraries bring their own
# rules (Media3, OkHttp, coroutines, Coil); these cover what they don't.

# Keep class names: crash reports stay readable, and the source is public anyway.
-dontobfuscate

# NewPipeExtractor decodes YouTube's player JavaScript with Rhino, which loads many of its
# classes by name, and the extractor itself finds some classes by reflection (e.g. the
# "3 days ago" parsers for each language). Both are kept whole rather than risk breaking
# extraction in ways only some videos or languages would show.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }

# Rhino also works with desktop-Java APIs that Android doesn't have: JavaBeans (converting
# Java objects to JSON), javax.script (its script-engine wrapper) and jdk.dynalink (its
# bytecode compiler). NewPipeExtractor uses none of them: it runs Rhino interpreted.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
