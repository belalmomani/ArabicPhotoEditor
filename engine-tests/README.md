# اختبارات المحرك على JVM

محرك التدرجات (حزمة `gradient` بدون الواجهة) كود Kotlin صرف، فيمكن ترجمته واختباره بدون أندرويد:

```
kotlinc app/src/main/java/com/example/arabicphotoeditor/gradient/{ColorMath,Model,GradientRenderer,SourceRenderers,Effects,ColorLab,Dna,Animator,GifEncoder}.kt -d out
kotlinc engine-tests/ColorLabDnaTest.kt -cp out -d tests
java -cp tests:out:<kotlin-stdlib.jar> ColorLabDnaTest.Test1Kt
```

(`AnimationGifTest` يكتب ملفات GIF يمكن فتحها لفحصها، و`SheetTest` يكتب صور إطارات خام.)
ملفات `stubs/` تسمح بترجمة `ProjectIO` و`AnimationExport` للتحقق من صحة الأنواع فقط (لا تعمل فعلياً).
