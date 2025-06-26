package org.honeyseeker;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.regex.Matcher;

public class XmlCharsetDetector {
    private static final Pattern QUOTE_PATTERN = Pattern.compile("[\"']");
    private static final int CONFIDENCE_THRESHOLD = 50;
    private static final int MIN_ENCODING_NAME_LENGTH = 3;

    public static Charset getCharsetFromXml(ZipFile zip, ZipEntry entry, Logger logger) throws IOException {
        // Читаем первые несколько килобайт файла для детекции кодировки
        byte[] fileStartBytes = readFileStart(zip, entry, 4096);

        // Определяем кодировку с помощью ICU4J
        CharsetDetector detector = new CharsetDetector();
        detector.setText(fileStartBytes);
        CharsetMatch[] matches = detector.detectAll();

        // Пытаемся определить кодировку из XML-декларации (с использованием ICU4J)
        Charset declaredCharset = detectDeclaredCharset(fileStartBytes, matches);

        // Выбираем лучшую кодировку
        Charset finalCharset = selectBestCharset(declaredCharset, matches);

        // Логируем предупреждения, если есть расхождения
        logEncodingWarnings(entry.getName(), declaredCharset, matches, logger);

        return finalCharset != null ? finalCharset : StandardCharsets.UTF_8;
    }

    @SuppressWarnings("SameParameterValue")
    private static byte[] readFileStart(ZipFile zip, ZipEntry entry, int numBytes) throws IOException {
        try (InputStream is = zip.getInputStream(entry)) {
            byte[] buffer = new byte[numBytes];
            int bytesRead = is.read(buffer);
            return bytesRead == -1 ? new byte[0] : Arrays.copyOf(buffer, bytesRead);
        }
    }

    private static Charset detectDeclaredCharset(byte[] fileStartBytes, CharsetMatch[] charsetMatches) {
        // Список кодировок для попыток, начиная с UTF-8
        List<Charset> charsetsToTry = new ArrayList<>();
        charsetsToTry.add(StandardCharsets.UTF_8);

        // Добавляем кодировки из ICU4J, если они есть
        if (charsetMatches != null) {
            for (CharsetMatch match : charsetMatches) {
                try {
                    Charset charset = Charset.forName(match.getName());
                    if (!charsetsToTry.contains(charset)) {
                        charsetsToTry.add(charset);
                    }
                } catch (IllegalArgumentException e) {
                    // Пропускаем неподдерживаемые кодировки
                }
            }
        }

        // Пробуем все кодировки по очереди
        for (Charset charset : charsetsToTry) {
            try {
                String firstLines = new String(fileStartBytes, charset);
                if (firstLines.startsWith("<?xml")) {
                    int encodingIndex = firstLines.indexOf("encoding=");
                    if (encodingIndex != -1) {
                        Matcher matcher = QUOTE_PATTERN.matcher(firstLines);
                        if (matcher.find(encodingIndex + 10)) {
                            String encoding = firstLines.substring(
                                    encodingIndex + 10,
                                    matcher.start()
                            ).trim();
                            if (encoding.length() >= MIN_ENCODING_NAME_LENGTH) {
                                try {
                                    return Charset.forName(encoding);
                                } catch (IllegalArgumentException e) {
                                    // Некорректное имя кодировки, продолжаем пробовать
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Пробуем следующую кодировку
            }
        }

        return null;
    }

    private static Charset selectBestCharset(Charset declaredCharset, CharsetMatch[] matches) {
        // Если есть только декларированная кодировка
        if (matches == null || matches.length == 0) {
            return declaredCharset != null ? declaredCharset : StandardCharsets.UTF_8;
        }

        // Если есть и декларированная кодировка, и детектированная
        if (declaredCharset != null) {
            // Проверяем, совпадает ли декларированная кодировка с детектированной
            for (CharsetMatch match : matches) {
                try {
                    Charset detectedCharset = Charset.forName(match.getName());
                    if (detectedCharset.equals(declaredCharset)) {
                        return declaredCharset; // Совпадение - доверяем декларации
                    }
                } catch (IllegalArgumentException e) {
                    // Некорректное имя кодировки, продолжаем пробовать
                }
            }

            // Если не совпадает, выбираем ту, у которой выше уверенность
            if (matches[0].getConfidence() >= CONFIDENCE_THRESHOLD) {
                try {
                    return Charset.forName(matches[0].getName());
                } catch (IllegalArgumentException e) {
                    return declaredCharset;
                }
            }
        }

        // Если декларированной кодировки нет, берем лучшую из детектированных
        try {
            return matches[0].getConfidence() >= CONFIDENCE_THRESHOLD ?
                    Charset.forName(matches[0].getName()) :
                    StandardCharsets.UTF_8;
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static void logEncodingWarnings(String name, Charset declaredCharset, CharsetMatch[] matches,
                                            Logger logger) {
        if (declaredCharset == null || matches == null || matches.length == 0) {
            return;
        }

        try {
            Charset detectedCharset = Charset.forName(matches[0].getName());
            if (!detectedCharset.equals(declaredCharset)) {
                logger.logWarn(String.format(
                        "%s: encoding mismatch. Declared: %s, Detected: %s (confidence: %d%%)%n",
                        name,
                        declaredCharset.name(),
                        detectedCharset.name(),
                        matches[0].getConfidence())
                );

                if (matches[0].getConfidence() > CONFIDENCE_THRESHOLD) {
                    logger.logWarn(
                            name + ": detected encoding has high confidence, using it instead of declared one"
                    );
                }
            }
        } catch (IllegalArgumentException e) {
            logger.logWarn(name + ": unsupported encoding detected: " + matches[0].getName());
        }
    }
}
