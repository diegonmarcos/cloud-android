# Consumer rules for libs:media-center.
# Room entities/DAOs and the provider contract are reflected over by Room and
# Hilt in the consuming app, so their names must survive R8 there.
-keep class com.dot.gallery.cloud.data.entity.** { *; }
-keep interface com.dot.gallery.cloud.data.dao.** { *; }
