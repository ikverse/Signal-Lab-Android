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
