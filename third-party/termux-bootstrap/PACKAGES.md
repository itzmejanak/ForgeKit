# Termux bootstrap package registry

This inventory comes from `var/lib/dpkg/status` inside the pinned bootstrap. License values and
source links come from the Termux build recipes at commit
[`5a6d1c1eb868795dce83a6c269387b9f82d21805`](https://github.com/termux/termux-packages/tree/5a6d1c1eb868795dce83a6c269387b9f82d21805).
Split packages link to their parent recipe.

| Package | Version | Declared license(s) | Termux recipe |
|---|---|---|---|
| `apt` | `2.8.1-1` | GPL-2.0 | [`packages/apt/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/apt/build.sh) |
| `bash` | `5.2.37-2` | GPL-3.0 | [`packages/bash/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/bash/build.sh) |
| `bzip2` | `1.0.8-6` | BSD | [`packages/libbz2/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libbz2/build.sh) |
| `ca-certificates` | `1:2025.02.25` | MPL-2.0 | [`packages/ca-certificates/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/ca-certificates/build.sh) |
| `command-not-found` | `2.4.0-68` | Apache-2.0 | [`packages/command-not-found/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/command-not-found/build.sh) |
| `coreutils` | `9.6-1` | GPL-3.0 | [`packages/coreutils/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/coreutils/build.sh) |
| `curl` | `8.12.1` | MIT | [`packages/libcurl/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libcurl/build.sh) |
| `dash` | `0.5.12` | BSD 3-Clause | [`packages/dash/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/dash/build.sh) |
| `debianutils` | `5.21` | GPL-2.0 | [`packages/debianutils/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/debianutils/build.sh) |
| `dialog` | `1.3-20240307-0` | LGPL-2.1 | [`packages/dialog/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/dialog/build.sh) |
| `diffutils` | `3.11` | GPL-3.0 | [`packages/diffutils/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/diffutils/build.sh) |
| `dos2unix` | `7.5.2` | BSD 2-Clause | [`packages/dos2unix/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/dos2unix/build.sh) |
| `dpkg` | `1.22.6-1` | GPL-2.0 | [`packages/dpkg/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/dpkg/build.sh) |
| `ed` | `1.21.1` | GPL-2.0 | [`packages/ed/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/ed/build.sh) |
| `findutils` | `4.10.0` | GPL-3.0 | [`packages/findutils/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/findutils/build.sh) |
| `gawk` | `5.3.0` | GPL-3.0 | [`packages/gawk/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/gawk/build.sh) |
| `gpgv` | `2.4.5-3` | GPL-3.0 | [`packages/gnupg/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/gnupg/build.sh) |
| `grep` | `3.11` | GPL-3.0 | [`packages/grep/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/grep/build.sh) |
| `gzip` | `1.13` | GPL-3.0 | [`packages/gzip/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/gzip/build.sh) |
| `inetutils` | `2.6` | GPL-3.0 | [`packages/inetutils/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/inetutils/build.sh) |
| `less` | `668` | GPL-3.0, custom | [`packages/less/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/less/build.sh) |
| `libandroid-glob` | `0.6-2` | BSD 3-Clause | [`packages/libandroid-glob/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libandroid-glob/build.sh) |
| `libandroid-selinux` | `14.0.0.11` | Public Domain | [`packages/libandroid-selinux/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libandroid-selinux/build.sh) |
| `libandroid-support` | `29` | Apache-2.0, MIT | [`packages/libandroid-support/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libandroid-support/build.sh) |
| `libassuan` | `3.0.1-2` | GPL-2.0 | [`packages/libassuan/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libassuan/build.sh) |
| `libbz2` | `1.0.8-6` | BSD | [`packages/libbz2/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libbz2/build.sh) |
| `libc++` | `27c` | NCSA | [`packages/libc++/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libc++/build.sh) |
| `libcap-ng` | `2:0.8.5` | LGPL-2.1 | [`packages/libcap-ng/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libcap-ng/build.sh) |
| `libcurl` | `8.12.1` | MIT | [`packages/libcurl/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libcurl/build.sh) |
| `libevent` | `2.1.12-2` | BSD 3-Clause | [`packages/libevent/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libevent/build.sh) |
| `libgcrypt` | `1.11.0` | GPL-2.0, LGPL-2.1, BSD 3-Clause, MIT, Public Domain | [`packages/libgcrypt/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libgcrypt/build.sh) |
| `libgmp` | `6.3.0-1` | LGPL-3.0 | [`packages/libgmp/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libgmp/build.sh) |
| `libgnutls` | `3.8.9` | LGPL-2.1, GPL-3.0 | [`packages/libgnutls/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libgnutls/build.sh) |
| `libgpg-error` | `1.50` | LGPL-2.1 | [`packages/libgpg-error/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libgpg-error/build.sh) |
| `libiconv` | `1.18` | LGPL-2.1, GPL-3.0 | [`packages/libiconv/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libiconv/build.sh) |
| `libidn2` | `2.3.7` | LGPL-3.0, GPL-2.0, GPL-3.0 | [`packages/libidn2/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libidn2/build.sh) |
| `liblz4` | `1.10.0` | GPL-2.0 | [`packages/liblz4/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/liblz4/build.sh) |
| `liblzma` | `5.8.0` | LGPL-2.1, GPL-2.0, GPL-3.0 | [`packages/liblzma/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/liblzma/build.sh) |
| `libmd` | `1.1.0` | custom | [`packages/libmd/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libmd/build.sh) |
| `libmpfr` | `4.2.1` | LGPL-3.0 | [`packages/libmpfr/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libmpfr/build.sh) |
| `libnettle` | `3.10.1` | GPL-2.0, LGPL-3.0 | [`packages/libnettle/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libnettle/build.sh) |
| `libnghttp2` | `1.65.0` | MIT | [`packages/libnghttp2/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libnghttp2/build.sh) |
| `libnghttp3` | `1.8.0` | MIT | [`packages/libnghttp3/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libnghttp3/build.sh) |
| `libnpth` | `1.6-2` | LGPL-2.0 | [`packages/libnpth/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libnpth/build.sh) |
| `libsmartcols` | `2.40.2-3` | GPL-3.0-or-later, GPL-2.0-or-later, LGPL-2.1-or-later, BSD 3-Clause, BSD, ISC | [`packages/util-linux/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/util-linux/build.sh) |
| `libssh2` | `1.11.1` | BSD 3-Clause | [`packages/libssh2/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libssh2/build.sh) |
| `libtirpc` | `1.3.6` | BSD | [`packages/libtirpc/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libtirpc/build.sh) |
| `libunbound` | `1.22.0` | BSD 3-Clause | [`packages/libunbound/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libunbound/build.sh) |
| `libunistring` | `1.3` | LGPL-3.0, GPL-2.0 | [`packages/libunistring/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/libunistring/build.sh) |
| `lsof` | `4.99.4` | custom | [`packages/lsof/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/lsof/build.sh) |
| `nano` | `8.3` | GPL-3.0 | [`packages/nano/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/nano/build.sh) |
| `ncurses` | `6.5.20240831-2` | MIT | [`packages/ncurses/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/ncurses/build.sh) |
| `net-tools` | `2.10.0` | GPL-2.0 | [`packages/net-tools/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/net-tools/build.sh) |
| `openssl` | `1:3.4.1` | Apache-2.0 | [`packages/openssl/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/openssl/build.sh) |
| `patch` | `2.7.6-4` | GPL-2.0 | [`packages/patch/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/patch/build.sh) |
| `pcre2` | `10.45` | BSD 3-Clause | [`packages/pcre2/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/pcre2/build.sh) |
| `procps` | `3.3.17-5` | LGPL-2.0 | [`packages/procps/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/procps/build.sh) |
| `psmisc` | `23.7` | GPL-2.0 | [`packages/psmisc/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/psmisc/build.sh) |
| `readline` | `8.2.13` | GPL-3.0 | [`packages/readline/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/readline/build.sh) |
| `resolv-conf` | `1.3` | Public Domain | [`packages/resolv-conf/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/resolv-conf/build.sh) |
| `sed` | `4.9-1` | GPL-3.0 | [`packages/sed/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/sed/build.sh) |
| `tar` | `1.35` | GPL-3.0 | [`packages/tar/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/tar/build.sh) |
| `termux-am` | `0.8.0-1` | Apache-2.0 | [`packages/termux-am/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/termux-am/build.sh) |
| `termux-am-socket` | `1.5.0` | GPL-3.0 | [`packages/termux-am-socket/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/termux-am-socket/build.sh) |
| `termux-core` | `0.3.0` | MIT | [`packages/termux-core/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/termux-core/build.sh) |
| `termux-exec` | `1:2.3.0` | Apache-2.0 | [`packages/termux-exec/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/termux-exec/build.sh) |
| `termux-keyring` | `3.12-1` | Apache-2.0 | [`packages/termux-keyring/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/termux-keyring/build.sh) |
| `termux-licenses` | `2.1` | GPL-3.0 | [`packages/termux-licenses/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/termux-licenses/build.sh) |
| `termux-tools` | `1.45.0` | GPL-3.0 | [`packages/termux-tools/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/termux-tools/build.sh) |
| `unzip` | `6.0-9` | BSD | [`packages/unzip/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/unzip/build.sh) |
| `util-linux` | `2.40.2-3` | GPL-3.0-or-later, GPL-2.0-or-later, LGPL-2.1-or-later, BSD 3-Clause, BSD, ISC | [`packages/util-linux/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/util-linux/build.sh) |
| `xxhash` | `0.8.3` | BSD, GPL-2.0 | [`packages/xxhash/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/xxhash/build.sh) |
| `xz-utils` | `5.8.0` | LGPL-2.1, GPL-2.0, GPL-3.0 | [`packages/liblzma/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/liblzma/build.sh) |
| `zlib` | `1.3.1` | ZLIB | [`packages/zlib/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/zlib/build.sh) |
| `zstd` | `1.5.7` | GPL-2.0 | [`packages/zstd/build.sh`](https://github.com/termux/termux-packages/blob/5a6d1c1eb868795dce83a6c269387b9f82d21805/packages/zstd/build.sh) |
