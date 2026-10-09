package id.fayda.verification.domain;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Blob;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case study's headline privacy claim is that no biometric image byte is ever persisted. A
 * claim like that is only worth anything if something enforces it, so this test walks the whole
 * entity graph and the whole baseline schema looking for a column that could hold one.
 *
 * <p>It checks three ways an image could sneak in: a binary Java type, a String column wide
 * enough to carry base64, and a binary SQL type in the migration.</p>
 */
class EntityRetentionTest {

    /** Anything above this is not a name, a code or a hash — it is a smuggled payload. */
    private static final int MAX_STRING_COLUMN_LENGTH = 1024;

    private static final Pattern BINARY_SQL_TYPES = Pattern.compile(
            "\\b(BINARY|VARBINARY|IMAGE|BLOB|TINYBLOB|TINYTEXT|VARBINARY\\(max\\)|FILESTREAM|"
                    + "ROWVERSION|TIMESTAMP)\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    void noEntityFieldHoldsBinaryData() throws ClassNotFoundException {
        List<Class<?>> entities = entities();
        assertThat(entities).as("the entity scan must find the mapped classes").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Class<?> entity : entities) {
            for (Field field : entity.getDeclaredFields()) {
                if (isMappedScalar(field)) {
                    offenders.add(entity.getSimpleName() + "." + field.getName());
                }
            }
        }
        assertThat(offenders).as("columns that could hold image bytes").isEmpty();
    }

    @Test
    void noStringColumnIsWideEnoughToCarryAPayload() throws ClassNotFoundException {
        List<String> tooWide = new ArrayList<>();
        for (Class<?> entity : entities()) {
            for (Field field : entity.getDeclaredFields()) {
                if (!isMappedScalar(field) || field.getType() != String.class) {
                    continue;
                }
                Column column = field.getAnnotation(Column.class);
                int length = column == null ? 255 : column.length();
                if (length > MAX_STRING_COLUMN_LENGTH) {
                    tooWide.add(entity.getSimpleName() + "." + field.getName() + "(" + length + ")");
                }
            }
        }
        assertThat(tooWide).as("string columns wider than " + MAX_STRING_COLUMN_LENGTH).isEmpty();
    }

    @Test
    void theBaselineSchemaDeclaresNoBinaryColumnType() throws java.io.IOException {
        // Comments are stripped first: the file talks about "image bytes" to say it stores none.
        String ddl = stripComments(baselineSql());

        Matcher matcher = BINARY_SQL_TYPES.matcher(ddl);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        assertThat(found).as("binary column types in V1__baseline.sql").isEmpty();
    }

    @Test
    void theSchemaHasAScoreAndHashShapeRatherThanAnImageShape() throws java.io.IOException {
        String sql = baselineSql();

        // The columns that do exist are the ones the decision needs.
        assertThat(sql).contains("id_number_hash", "request_key_hash", "composite_score",
                "threshold_version");
        assertThat(sql).doesNotContainIgnoringCase("image_bytes", "photo", "selfie", "frame");
    }

    private static String baselineSql() throws java.io.IOException {
        return new ClassPathResource("db/migration/V1__baseline.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?m)--.*$", " ");
    }

    private static boolean isMappedScalar(Field field) {
        if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()
                || field.isAnnotationPresent(Transient.class)) {
            return false;
        }
        Class<?> type = field.getType();
        return type == byte[].class || type == Byte[].class || Blob.class.isAssignableFrom(type)
                || java.sql.Clob.class.isAssignableFrom(type);
    }

    private static List<Class<?>> entities() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("id.fayda.verification")) {
            found.add(Class.forName(definition.getBeanClassName()));
        }
        return found;
    }
}
