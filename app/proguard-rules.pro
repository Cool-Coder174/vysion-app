# Only matters if minify is turned on (it is off). The ExecuTorch AAR's own proguard.txt keeps its
# @DoNotStrip / native members; this is belt-and-braces (docs/stack.md §2.4).
-keep class org.pytorch.executorch.** { *; }
