# Photo Sorter (Android)

An Android app that sorts the photos on your phone into albums, either
**by location** (where each photo was taken) or **by content** (what's in it).
It works on Android 10 and newer.

## Install

1. On your phone, open the repository's **Releases** page on GitHub and pick
   **Photo Sorter (latest build)**. Every push that changes the app rebuilds it.
2. Tap `PhotoSorter.apk` to download it, then open it.
3. Android will ask you to allow installing apps from your browser.
   Allow it, then tap **Install**.

The APK is signed with a throwaway debug key that changes with each build.
To install a newer build, uninstall the old one first.

## Use

1. Tap **Choose folder** and pick the folder to sort, for example
   `DCIM/Camera`. The app asks for no other permissions. It can only see
   the folder you pick and its subfolders, and it gives up access to the
   previous folder when you pick a new one. (Android doesn't let apps pick
   the root of internal storage or `Download`; pick a folder inside them.)
2. Choose a mode:
   - **By location**: photos are grouped by city, region or country.
     Place names come from Android's geocoder, which needs internet.
     Offline, photos are grouped by rounded coordinates instead.
     Photos with no GPS data go to *Unknown location*.
   - **By content**: type your categories in your own words, e.g.
     `Pets, People, Food, Nature, Documents, Screenshots` or `my dog, receipts, beach`.
     Each photo goes to the category that fits best. Choose the recognition engine:
     - **On this phone** (default): OpenAI's CLIP model runs on the phone with
       ONNX Runtime. Nothing is uploaded and it's free. The first run
       downloads the model once (about 150 MB). Photos below the
       *Minimum confidence* go to *Other*.
     - **Claude (cloud)**: the most accurate option. A reduced copy of each
       photo (768 px) goes to Anthropic's API with your categories. You need
       your own API key from console.anthropic.com, and usage is billed to it.
       Rough cost per 1,000 photos: Opus 5.5 about $6 (default, most accurate),
       Sonnet 5.5 about $3, Haiku 5.5 about $0.15. Photos the API can't handle
       go to *Not classified*.
3. Optionally set **Photos to review** (e.g. `200`) to work in batches,
   starting from the **oldest** photos (or switch to **Newest first**). Leave it empty to review the whole folder. With
   **Skip photos already sorted** on (the default), photos already in the
   `Sorted` folder are skipped, so each run picks up the next batch.
4. Tap **Preview albums**. Nothing on your phone changes yet.
5. Tap **Copy into albums** to keep your originals in place, or
   **Move into albums**. The albums are created as subfolders of a
   `Sorted` folder inside the folder you picked, e.g. `DCIM/Camera/Sorted/Dog`,
   and show up as albums in your gallery app.

The `Sorted` folder is skipped the next time you sort the same folder.

## Build it yourself

```bash
cd photo-sorter-app
./gradlew assembleDebug    # needs JDK 17 and the Android SDK (ANDROID_HOME)
# -> app/build/outputs/apk/debug/app-debug.apk
```

The GitHub Actions workflow `.github/workflows/android-app.yml` does the same
thing and publishes the APK as the `photo-sorter-latest` release.

## Code

| File | What it does |
|---|---|
| `PhotoStore.kt` | Lists photos and reads EXIF GPS in the picked folder (Storage Access Framework); copies and moves files |
| `Groupers.kt` | `LocationGrouper` (Geocoder) and folder-name helpers |
| `ClipClassifier.kt`, `ClipTokenizer.kt` | On-device CLIP: model download, tokenizer, image/text encoders |
| `ClaudeClassifier.kt` | Cloud classification with the Claude API (Anthropic Java SDK) |
| `SorterViewModel.kt` | Runs the preview, copy and move steps in the background |
| `MainActivity.kt` | The Compose UI and permission requests |
