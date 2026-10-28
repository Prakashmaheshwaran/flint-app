# Manifest entry points and SDK callbacks are externally invoked. Android's
# default rules also retain manifest roots; these explicit rules make Flint's
# enforcement boundary reviewable when components are moved between modules.
-keep class com.flint.peakfocus.** extends android.app.Application { *; }
-keep class com.flint.peakfocus.** extends android.app.Activity { *; }
-keep class com.flint.peakfocus.** extends android.app.Service { *; }
-keep class com.flint.peakfocus.** extends android.content.BroadcastReceiver { *; }

# Persisted enum names and model/codec shapes are a compatibility boundary.
# Current stores use explicit codecs rather than reflective JSON serialization.
# Keep this small domain/storage surface stable through future serializer use.
-keep class com.flint.peakfocus.core.model.** { *; }
-keep class com.flint.peakfocus.core.datastore.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Preserve the meaning of the packaged local-first descriptor scan under R8.
# These names stay recognizable IF an accidental connected dependency is live;
# unused code may still be removed. Neither package belongs in release.
-keepnames class com.google.firebase.**
-keepnames class com.flint.peakfocus.feature.connected.**

# Design-time preview entry points/tooling must not survive the release graph.
-checkdiscard class androidx.compose.ui.tooling.**
-checkdiscard class com.flint.peakfocus.** {
    @androidx.compose.ui.tooling.preview.Preview <methods>;
}
