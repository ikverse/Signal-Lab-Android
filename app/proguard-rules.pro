# Release builds are minified. Shrinking removes code nothing reaches; names are kept (no
# obfuscation) so a stack trace out of a release build can be read without a mapping file.
-dontobfuscate

# Room (added with the data layer) builds its generated database class by reflection, and the
# library's own rule keeps the class but lets the constructor be shrunk off it. EGX Analyzer shipped
# a release that would not open because of exactly this. When Room arrives, name the member here,
# and read usage.txt for `<init>()` under any `_Impl` class after every dependency bump.

# The pages inside web views (the chart check) call back into the app by name through addJavascriptInterface.
# Nothing in Kotlin calls those methods, so without this the shrinker removes them and the page's call silently does nothing.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
