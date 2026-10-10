-allowaccessmodification

# PDFBox font classes, kept from upstream for font loading and reflection.
-keep class com.tom_roush.pdfbox.pdmodel.font.PDFont { *; }
-keep class com.tom_roush.pdfbox.pdmodel.font.PDType0Font { *; }
-keep class com.tom_roush.pdfbox.pdmodel.font.PDType1Font { *; }
-keep class com.tom_roush.pdfbox.pdmodel.font.PDTrueTypeFont { *; }
-keep class com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2 { *; }
-keep class com.tom_roush.pdfbox.pdmodel.font.PDSimpleFont { *; }
-keep class com.tom_roush.pdfbox.pdmodel.font.PDType3Font { *; }

# Optional PDFBox codecs and APIs (JPEG 2000, StAX) that are not on Android and that the app never ships.
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter
-dontwarn com.gemalto.jp2.**
-dontwarn javax.xml.stream.**
# Bouncy Castle's LDAP and DNS certificate lookups reference javax.naming (JNDI), which Android lacks; nothing in the app calls them.
-dontwarn org.bouncycastle.**
