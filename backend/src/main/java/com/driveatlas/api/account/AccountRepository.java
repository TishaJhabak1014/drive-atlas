package com.driveatlas.api.account;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID upsert(String subject, String email, String displayName) {
        return jdbc.queryForObject("""
                INSERT INTO google_account (google_subject, email, display_name)
                VALUES (?, ?, ?)
                ON CONFLICT (google_subject) DO UPDATE
                    SET email = EXCLUDED.email,
                        display_name = EXCLUDED.display_name,
                        updated_at = now()
                RETURNING id
                """, UUID.class, subject, email, displayName);
    }

    public void delete(String subject) {
        jdbc.update("DELETE FROM google_account WHERE google_subject = ?", subject);
    }
}
