> [!IMPORTANT]
> If you want your own ksud binary, compile from diabl0w's fork of KernelSU: https://github.com/diabl0w/KernelSU

# CVE-2026-43284

## What's changed in this fork

- **OneUI-style UI rework** — OneUI look throughout: card + custom-drawn OneUI switches (constant-size thumb), OneUI-style bottom fade over the log, One UI-style floating run button (state-aware: shows "Run exploit" / disabled "Rooted")
- **Cleaner output log** — byte counters, hook internals and native markers filtered out; styled section headers; the log of the **last run is persisted** and shown on app open, then cleared on reboot
- **Share log button** — saves the log to `Downloads/dirtyfrag_log.txt` and opens the Downloads screen
- **Hardened autorun** — boot-time soft reboot is a separate toggle ("Auto reboot — soft reboots after root"), **off by default**; a failed boot-time run disables autorun instead of retry-looping (protection against the RescueParty boot-loop brick reported in the field); no hidden defaults anywhere
- **APK shrunk 14.6 MB → ~6.2 MB** via R8 minification + resource shrinking (`proguard-rules.pro`)

Credits:
- DFRoot — base of this fork: https://github.com/diabl0w/DFRoot
- Original PoC and various code: https://github.com/lsposed/lspromise
- Selinux Permissive kernel modules and various code: https://github.com/polygraphene/DFReroot
- Unprivileged XFRM socket method: https://github.com/combeng6th/DirtyInit

## Usage

Install KernelSU (download "manager" file) from actions flow: https://github.com/tiann/KernelSU/actions/runs/35973514328

## Features

- Start on Boot
- Automatic soft reboot 
- RO Partition Protection
- Hide Selinux Modifications in KSU
- Shizuku not needed — regain root without WiFi!

> [!WARNING]
> I am not responsible for any damage to your device.

## Supported Devices

Ephemeral root for Samsung devices (and possibly others) w/ locked bootloaders vulnerable to DirtyFrag (CVE-2026-43284) 

| KMI Version | Verified |
|---|---|
| android12-5.10 | Untested |
| android13-5.10 | Untested |
| android13-5.15 | Untested |
| android14-5.15 | Untested |
| android14-6.1 | Untested |
| android15-6.6 | Yes |
| android16-6.12 | Yes |
| android17-6.18 | Untested |

## How it works

The Android kernel decrypts AES-CBC ESP packets directly into the page cache of files open for `splice()`. By crafting `IV = AES_ECB_DEC(key, current_content) ⊕ desired_content`, any 16-byte-aligned block in a mapped shared library can be overwritten without write permission and without copy-on-write.

The exploit uses this primitive to patch shellcode into `libc++.so` and `libc.so` in the kernel's page cache. The next privileged call to those functions runs the shellcode and installs KernelSU.

### Exploit chain

1. **IpSec transform** — App allocates a `UdpEncapsulationSocket` + SPI and builds an AES-CBC/HMAC-SHA256 ESP transform via `IpSecManager`.

2. **splicehelper → crash_dump64** — helper binary spliced into `/apex/com.android.runtime/bin/crash_dump64` via the CBC primitive. `crash_dump64` can be called by unprivileged app with `type_transform` and gives read access to vendor library pages and splices them into a pipe so the parent can compute correct IVs. 

3. **dirtyfrag.ko → libbinderdebug.so** — The kernel module is written into `/vendor/lib64/libbinderdebug.so` with `vendor_file` label that can be modprobe'd

4. **libc++ hook** (runs in init, uid=0, tid=1) — entrypoint via createorphanprocess. patched with shellcode that forks, sets the child's SELinux exec context to `u:r:vendor_modprobe:s0`, and execs `/vendor/bin/modprobe`.

5. **libc hook** (runs in vendor_modprobe, uid=0) — Shellcode patched into `__libc_init`. When vendor_modprobe starts:
   - Calls `finit_module` to load dirtyfrag.ko
   - Opens ksud from the app's memfd via `/proc/<pid>/fd/<n>`, copies to `/dev/.ksud` and `/data/system/ksud`
   - Unshares mount namespace, bind-mounts `/dev/.ksud` over `/system/bin/logcat` (DEFEX bypass via trusted path)
   - Forks and execs ksud through the bind-mounted path

6. **KernelSU daemon launched** — libc/libc++ patches are restored and crash_dump64 is fadvised out of cache.

