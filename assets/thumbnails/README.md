# Brand thumbnail sources

Android VectorDrawable thumbnails live in `app/src/main/res/drawable/thumb_*.xml` and are
registered in `Utilities/EntryThumbnail.kt`. The bundled set includes OfflineOTP's carry-over
batch of upstream andOTP requests, tracked in OfflineOTP issue #40 (81 requests, 79 distinct brands
after duplicate requests were collapsed). The issue also records the three logo-currency
follow-ups for Discord, netcup, and Proton Mail.

New vectors are adapted from these sources:

- [Simple Icons](https://github.com/simple-icons/simple-icons), v16.35.0, CC0 1.0.
- [Aegis Icons](https://github.com/aegis-icons/aegis-icons), using the matching primary issuer
  artwork. The pack's README describes its mixed upstream image rights.
- [SVG Logos](https://github.com/gilbarbara/logos), through Iconify's `@iconify-json/logos`
  v1.2.16, CC0 1.0.

For issuers not covered by those sets, the drawables use a compact custom monogram or waveform.
Monogram outlines are generated from DejaVu Sans Bold; see [DEJAVU-LICENSE.md](DEJAVU-LICENSE.md).
Some entries reuse an issuer's existing mark (for example, pgAdmin uses the PostgreSQL elephant
and VideoLAN uses the VLC mark). Brand marks identify the corresponding issuer only and do not
imply endorsement; all rights remain with their owners.
