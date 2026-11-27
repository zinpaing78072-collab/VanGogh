package vangogh.storage;

import io.vavr.control.Either;
import io.vavr.control.Option;
import vangogh.error.FetchError;
import vangogh.storage.model.User;

import java.time.Instant;

import static vangogh.db.Tables.USER;

/** The identity row. Accounts/login are parked; this is the single local user. */
public final class UserRepository {

    private final Database database;

    public UserRepository(Database database) {
        this.database = database;
    }

    public Either<FetchError, User> local() {
        return find(Database.LOCAL_USER_ID).flatMap(found -> found.isEmpty()
                ? Either.<FetchError, User>left(new FetchError.Storage(
                        "user.local", new IllegalStateException("local user row is missing")))
                : Either.<FetchError, User>right(found.get()));
    }

    public Either<FetchError, Option<User>> find(long id) {
        return Database.attempt("user.find", () -> database.read(context ->
                Option.of(context.select(USER.ID, USER.NAME, USER.CREATED_AT)
                                .from(USER)
                                .where(USER.ID.eq((int) id))
                                .fetchOne())
                        .map(row -> new User(
                                row.get(USER.ID),
                                row.get(USER.NAME),
                                row.get(USER.CREATED_AT)))));
    }

    /** Ensures the seeded row exists; idempotent. */
    public Either<FetchError, User> ensureLocal() {
        return Database.attempt("user.ensureLocal", () -> database.write(context -> {
            context.insertInto(USER)
                    .set(USER.ID, (int) Database.LOCAL_USER_ID)
                    .set(USER.NAME, "local")
                    .set(USER.CREATED_AT, (int) Instant.now().getEpochSecond())
                    .onConflict(USER.ID)
                    .doNothing()
                    .execute();
            var row = context.select(USER.ID, USER.NAME, USER.CREATED_AT)
                    .from(USER)
                    .where(USER.ID.eq((int) Database.LOCAL_USER_ID))
                    .fetchOne();
            if (row == null) {
                throw new IllegalStateException("local user row is missing after ensure");
            }
            return new User(row.get(USER.ID), row.get(USER.NAME), row.get(USER.CREATED_AT));
        }));
    }
}
