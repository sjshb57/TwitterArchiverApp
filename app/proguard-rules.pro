# 不混淆（只压缩去无用代码，保持可读，减小体积）
-dontobfuscate

# ── 本项目数据类 / 序列化 ──
-keepclassmembers @kotlinx.serialization.Serializable class io.github.twitterarchiver.** {
    <fields>;
}
-keepclassmembers class io.github.twitterarchiver.** { *** Companion; }

# ── kotlinx.serialization ──
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class io.github.twitterarchiver.**$$serializer { *; }
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static <1>$Companion Companion;
}

# ── Ktor ──
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.slf4j.**
