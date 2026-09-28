# Protect Gson from being obfuscated by R8
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Protect your app's data models
-keep class com.example.vehiclechecker.** { *; }
