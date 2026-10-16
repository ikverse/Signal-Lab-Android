# Release builds are minified. Shrinking removes code nothing reaches; names are kept (no
# obfuscation) so a stack trace out of a release build can be read without a mapping file.
-dontobfuscate

# The data layer is plain SQLiteOpenHelper with no reflection, so it needs no keep rule. If a library that builds classes by
# reflection is ever added (EGX Analyzer shipped a release that would not open because a generated constructor was shrunk off),
# name the member here and read usage.txt after every dependency bump.

# The pages inside web views (the chart check) call back into the app by name through addJavascriptInterface.
# Nothing in Kotlin calls those methods, so without this the shrinker removes them and the page's call silently does nothing.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# The Claude SDK (the analyst) reads and writes JSON with Jackson, which finds constructors, serializers and enum values by
# reflection. The SDK ships rules for its own annotated members; keeping the message models and Jackson's binding whole costs a few megabytes and
# removes most of the risk of a release that cannot talk to Claude while every debug build can.
-keep class com.anthropic.core.** { *; }
-keep class com.anthropic.models.messages.** { *; }
-keep class com.anthropic.helpers.MessageAccumulator** { *; }
-keep class com.anthropic.client.** { *; }
-keep class com.fasterxml.jackson.databind.** { *; }
-keep class com.fasterxml.jackson.core.** { *; }
-keep class com.fasterxml.jackson.module.kotlin.** { *; }
-keep class com.fasterxml.jackson.datatype.** { *; }
-dontwarn com.fasterxml.jackson.**
# The SDK's schema generator for structured outputs (unused here) refers to reflection types Android does not have.
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
