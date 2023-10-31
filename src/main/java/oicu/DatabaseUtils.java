package oicu;

import java.nio.file.Path;
import java.sql.*;
import java.util.Scanner;

import static oicu.AssertUtils.assertion;

public class DatabaseUtils {
    private static final String HASH_MAVEN_GAV = """
            CREATE TABLE hash_maven_gav (
                id INTEGER PRIMARY KEY,
                hash TEXT NOT NULL,
                group_id TEXT,
                artifact_id TEXT,
                version TEXT,
                is_private BOOLEAN NOT NULL
            )""";
    private static final String MAVEN_GA = """
            CREATE TABLE maven_ga (
                id INTEGER PRIMARY KEY,
                group_id TEXT NOT NULL,
                artifact_id TEXT NOT NULL,
                is_private BOOLEAN NOT NULL
            )""";

    public static Connection createDbConnection(Path databasePath) throws ClassNotFoundException, SQLException {
        // jdbc connection with create db file is not exists.
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
    }
    public static void checkDatabase(Connection dbConn) throws SQLException, ClassNotFoundException {
        try (Statement stmt = dbConn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='hash_maven_gav'");
            if (!rs.next()) {
                stmt.execute(HASH_MAVEN_GAV);
            } else {
                String currentSchema = rs.getString("sql");
                assertion(currentSchema.equals(HASH_MAVEN_GAV), "HASH_MAVEN_GAV table schema is not correct");
            }
            rs = stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='maven_ga'");
            if (!rs.next()) {
                stmt.execute(MAVEN_GA);
            } else {
                String currentSchema = rs.getString("sql");
                assertion(currentSchema.equals(MAVEN_GA), "MAVEN_GA table schema is not correct");
            }
        }
    }
}
