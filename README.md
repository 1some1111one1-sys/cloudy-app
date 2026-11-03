# Cloudy

Keep big files and app data in your cloud drive and use them on a phone with little storage. Cloudy stores files
in **Google Drive, MEGA, TeraBox or WebDAV** and brings only the parts that are being used to the phone, through a
RAM + disk cache whose size you set.

Drive sign-in works the same way as in the CloudMemory app: **TeraBox** through TeraBox's own website in a web view
(Cloudy never sees the password, it keeps only the session cookie, stored encrypted), **Google Drive** through the
system browser, **MEGA** with e-mail, password and an optional two-factor code.

## What it does today

- **Connect a drive** (several at once): Google Drive, MEGA, TeraBox, WebDAV.
- **Import a file** (APK, OBB, game data, anything) into a drive. It is uploaded straight to the cloud in 4 MB blocks;
  nothing is kept on the phone.
- **Streamed files**: each drive shows up in the system **Files app** and in other apps' file pickers. When an app
  opens a file, Android's proxy file descriptors (`StorageManager.openProxyFileDescriptor`, Android 8+) send every read
  and write to Cloudy, which answers from the cache or fetches only the blocks needed. Reading ahead hides some of the
  network delay on sequential reads.
- **Hard cache limits**: RAM and disk budgets per drive (default 128 MB and 1 GB). Least recently used blocks are dropped
  and fetched again when needed. Writes are uploaded in the background (every 5 seconds, when the app leaves the
  screen, and on "Upload now"); data that was not uploaded yet is never dropped to make room, and survives a crash if
  it was already on the cache disk. If the cloud is unreachable and the cache fills with unsent data, writes fail with
  an I/O error instead of the cache growing.

## What it does NOT do: run another app from the cloud

Android only runs apps that are installed in the phone's own storage, so Cloudy cannot make Android start an app
straight from a cloud drive. `ContainerEngine.java` is the empty place where a virtualization engine (an app
"container" such as VirtualApp-style projects) would go: it has to load the guest app's code, give it an identity and
redirect its file access, including native `open()`/`mmap()` calls, to the Cloudy store. That part is a large project of its
own and is **not built**. Many apps (especially games) also refuse to run in containers, and a game that reads
gigabytes at random will feel network latency however good the cache is.

What you do get now is the storage half: big files that live in your cloud, used on demand by any app that can open
a file from the Files app (media players, file managers, archive tools, emulators that take a file picker, ...).

## Get the APK (no Android Studio needed)

The repository has a GitHub Actions workflow. Open the **Actions** tab, run *Build APK* (it also runs on every push),
and download `Cloudy.apk` from the run's artifacts or from the **Releases** page. Every build is signed with a fresh
key, so uninstall the old build before installing a new one (and add your drives again).

### Google Drive needs your own client ID (once, about 5 minutes)

Google only lets an app reach Drive with a client ID. In the Google Cloud console: create a project, enable the
**Google Drive API**, set up the OAuth consent screen (External, publish it so sign-ins do not expire after 7 days),
then create an **OAuth client ID** of type **Desktop app**. Either paste the ID and secret into Cloudy when you add
the drive, or build them in: in the repository's *Settings > Secrets and variables > Actions* add a variable
`GOOGLE_CLIENT_ID` and a secret `GOOGLE_CLIENT_SECRET`; the next build then needs no typing. Google will say the app
is "not verified" (it is your own app): tap *Advanced* and continue.

## How the data is stored

```
<drive>/CloudyDrive/catalog                       the file list (paths, sizes, block ids)
<drive>/CloudyDrive/<fileId>/<n / 256>/<n>        block n of a file (4 MB each)
```

Blocks are spread over sub-folders of at most 256 entries, because TeraBox and others find a file by listing its
folder. Files in the cloud are **not** readable as normal files from the provider's own website: they are blocks.
Use Cloudy (or the Files app through Cloudy) to get at them.

## Limits to know about

- **One phone writes a drive at a time.** The catalog is last-writer-wins.
- **Replacing a block creates a new version on the service.** MEGA moves the old copy to the Rubbish Bin (empty it
  now and then, it counts toward your quota) and Google Drive keeps recent revisions for a while.
- **Speed**: every block is one request. Expect slow first reads and slow imports on TeraBox and MEGA especially;
  uploads are sequential.
- **TeraBox is unofficial** and may break whenever TeraBox changes its site; automated access may be against its terms.
- Deleting a big file deletes its blocks one by one, in the background on the next upload.
- Writes still in RAM at the moment the app is killed are lost; blocks already spilled to the cache disk are recovered.
- While a block is being uploaded, cache access from other threads can pause briefly.
- Not yet built and run on a real phone: see "Status" below.

## Status

- **Tested** (pure Java, `bash tests/run.sh`, needs a JDK 11+): the block store and cache (random I/O against a
  model, budgets, crash recovery, outage, read-ahead) and the block store on top of Google Drive, MEGA, TeraBox and
  WebDAV clients, run against in-process imitations of those services (import, overwrite, second phone reading, truncate,
  delete, 600-block folder sharding, errors). The Google Drive, MEGA, TeraBox and WebDAV clients and the sign-in
  code are the ones from CloudMemory.
- **Not tested**: any real Google, MEGA or TeraBox account (the imitations are not the real services; TeraBox is the
  least certain), and the Android screens and Files-app provider on a real device. The Android layer was checked only
  by the GitHub build.

## Layout

```
app/src/main/java/com/cloudy/app/
  MainActivity, AddDriveActivity       drives, cache size, import
  TeraBoxLoginActivity                 web view for signing in on TeraBox's website
  CloudyProvider                       the Files-app integration (DocumentsProvider + proxy file descriptors)
  Drives, Clients, AccountStore, SecretBox, OkHttpTransport
  ContainerEngine                      the not-built hook for running guest apps
  store/                               pure Java: BlockCache, CloudStore, DriveBackend (blocks on any drive client)
  core/                                pure Java, from CloudMemory: Google Drive, MEGA, TeraBox, WebDAV, S3 clients + sign-in
tests/                                 tests with in-process imitations of each service; run with `bash tests/run.sh`
```
