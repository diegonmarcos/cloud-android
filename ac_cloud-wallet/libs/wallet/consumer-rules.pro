# JavaMail / android-mail — keep all classes so R8 doesn't strip the
# dynamic provider lookup (Session.getStore("imaps") uses reflection).
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**
