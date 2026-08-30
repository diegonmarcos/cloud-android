# Room entities/DAOs and the provider contract are reflected over by Room and
# Hilt in the consuming app, so their names must survive R8 there.
-keep class com.diegonmarcos.mediacenter.**.entity.** { *; }
-keep interface com.diegonmarcos.mediacenter.**.dao.** { *; }
-keep class com.diegonmarcos.mediacenter.feature_node.domain.model.** { *; }
