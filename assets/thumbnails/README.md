# Brand thumbnail sources

The Android VectorDrawable resources live in `app/src/main/res/drawable/thumb_*.xml`.

The carry-over list in OfflineOTP issue #40 contains 81 upstream requests but only 79 distinct missing brands: STRATO is requested twice and Discogs #917 duplicates #860. Each brand is represented once in the picker. The three existing icons flagged for currency updates (Discord, netcup, and Proton Mail) are refreshed separately.

New vectors are adapted from these sources where a suitable logo is available:

- [Simple Icons](https://github.com/simple-icons/simple-icons), v16.35.0, CC0 1.0.
- [Aegis Icons](https://github.com/aegis-icons/aegis-icons), using the matching primary issuer artwork. The pack's README describes its mixed upstream image rights; the brands and marks remain the property of their respective owners.
- [SVG Logos](https://github.com/gilbarbara/logos), distributed through Iconify's `@iconify-json/logos` v1.2.16, CC0 1.0.

For issuers not represented in those icon sets, the new drawables use a compact, custom monogram or waveform so they still have a distinct selectable thumbnail. Monogram outlines are generated from DejaVu Sans Bold; see [DEJAVU-LICENSE.md](DEJAVU-LICENSE.md). The pgAdmin entry uses the PostgreSQL elephant mark; the VideoLAN entry uses the VLC media player mark; the Digitec.ch entry uses the Digitec Galaxus mark. Brand marks are included only to identify the corresponding issuer and do not imply endorsement.
