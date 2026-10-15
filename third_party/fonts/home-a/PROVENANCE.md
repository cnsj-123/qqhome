# Home A fonts

Read through local Figwright from file 57fBzB2YkI5bPZkGeNrMtR, frames 3:6 and 3:37, on 2026-10-15.

* Shalimar Regular (wordmark), Pacifico Regular (section labels), Almendra Regular (date stamps): unmodified binaries from https://github.com/google/fonts/tree/main/ofl. Each original OFL is retained here.
* AR PL UKai TW Book (date): extracted without glyph changes from the UKai collection distributed at https://deb.debian.org/debian/pool/main/f/fonts-arphic-ukai/fonts-arphic-ukai_0.2.20080216.2.orig.tar.bz2. ARPHICPL.TXT and FONTLOG retained. Full 24,133-codepoint cmap, not a fixed-label subset.

For R2, Owner supplied and explicitly authorized this project's embedding and redistribution of unmodified `PingFangSanShengTi-2.ttf` and `PingFangSaTuoTi-2.ttf`. Their internal families are 平方三生体 (6,891 codepoints) and 平方洒脱体 (6,893). They replace QianXia and Qiaomu respectively; neither replaced binary remains in the application resources. `演示悠然小楷.ttf`, supplied on 2026-10-14, remains the unmodified slideyouran face (6,955 codepoints). SHA-256:

* sansheng_regular.ttf: 15448492214ad928f10bc8d157603130d873de0a322a040a4e505f54ed035d8d
* satuo_regular.ttf: 1ae0f7c23ba4d044c5b85b9e1f70e6ef6e7686bd18eea69c4a94fe9eb544b229
* slideyouran_regular.ttf: eb8aa339ea0c4941bdcd7bacf0ae4b1a39362c2789cad2cb3f405b934f274ea8

Owner's project-specific authorization for the replacement faces is recorded in the R2 task; it is not a general public font license. Slidefont's distribution and OFL are available at https://github.com/maoken-fonts/slidefont (license retained here).

Huiwen-ZhengKai is now bundled unmodified from the original publisher's public endpoint https://huozi.cool/api/fonts/newfonts_HWZHENGKAI/download. Its public license API https://huozi.cool/api/license-config explicitly lists 汇文正楷 under free commercial / modification allowed, including embedded use. Snapshot retained in Huiwen-author-license.json. SHA-256: c5597407e8ebec614f75c649da9d2b959c3ff8db9a5fa07ded3818e2a0e7d109.

Owner requested the Home A hierarchy throughout the application, including Closet. Body uses Satuo; metadata uses Sansheng; page titles use UKai; large editorial empty messages use slideyouran. All Material and LifeText roles derive from LifeType. BundledText performs whole-run cmap fallback and preserves metrics. Current Figma AUTO line heights stay unspecified. Owner's explicit exception “好像没有随手一拍” retains the original P1 patch's slideyouran 30sp / 40sp line height / 2.1sp spacing, including the original fallback path.

Money uses the existing Noto Sans SC with `tnum`. The full font is retained to support user content. App code resolves font roles centrally in LifeFonts/LifeType; LifeText is the existing compact UI scale over those roles.

R2 removed unused Caveat, Special Elite, Noto Serif Medium/SemiBold and life_sans resources after checking all source/XML references. Font payload decreased from 86,702,652 to 72,415,176 bytes. Remaining full CJK faces serve distinct dynamic text roles: UKai dates/fallback, Huiwen ahead/facts, slideyouran empty messages, Satuo body, Sansheng metadata, and Noto numeric/final fallback. Fixed-label subsetting would drop arbitrary user text. ARPHICPL.TXT and FONTLOG retain their P1 bytes and encoding.
