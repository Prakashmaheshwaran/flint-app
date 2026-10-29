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

# Signing classification is packaged evidence even though app code does not
# branch on it. Do not let shrinking erase or rename the preview marker.
-keep class com.flint.peakfocus.BuildConfig {
    public static final boolean DEVELOPMENT_SIGNED_PREVIEW;
}

# Preserve the meaning of the packaged local-first descriptor scan under R8.
# Live members cannot inline into allowed callers and erase their original
# holder. Dead classes/members may be removed. Neither package belongs in release.
-keep,allowshrinking class com.google.firebase.** { *; }
-keep,allowshrinking class com.flint.peakfocus.feature.connected.** { *; }

# Design-time preview entry points/tooling must not survive the release graph.
-checkdiscard class androidx.compose.ui.tooling.**
-checkdiscard class com.flint.peakfocus.** {
    @androidx.compose.ui.tooling.preview.Preview <methods>;
}

# Glance's WorkManager creates this generated Room database through reflection.
# Retain its public no-argument constructor under release shrinking.
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}

# WorkManager 2.7.1's merged consumer rules keep InputMerger classes, but omit
# their constructors. R8 full mode does not implicitly retain a no-arg constructor;
# WorkerWrapper reflectively creates this merger for Glance's actual widget jobs.
# ListenableWorker constructors (including Glance SessionWorker) are already kept
# explicitly by those consumer rules. Keep this missing constructor only.
-keep class androidx.work.OverwritingInputMerger {
    public <init>();
}
