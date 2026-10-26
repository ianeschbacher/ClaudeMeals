# ClaudeMeals

A weekly meal planner built around Dr. Greger's Daily Dozen. Spring Boot 3.4.3 / Java 21 /
SQLite, with a single-file static frontend. Runs locally on port 8083.

## Running it

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk/25/libexec/openjdk.jdk/Contents/Home mvn clean package -DskipTests
launchctl unload ~/Library/LaunchAgents/com.example.claudemeals.plist
launchctl load   ~/Library/LaunchAgents/com.example.claudemeals.plist
```

Served at <http://localhost:8083>. The launchd plist must point at the real JDK binary, not
`/usr/bin/java` — the macOS stub fails under launchd. Always `mvn clean`: a plain `package`
can bundle a stale `static/` file.

## How it works

- **Recipes** carry two things beyond a name: how many servings one batch yields and for which
  meals ("1 dinner + 2 lunches", or "2 servings, breakfast *or* snack"), and which Daily Dozen
  items one serving ticks off.
- **A period** is seven days starting whenever you plan — no weekday alignment.
- **The planner** fills each meal, maximising Daily Dozen coverage, capped at three dishes
  started from scratch per day. Leftovers are free: one batch feeding four meals is one session.
- **Sides** are never scheduled. A day that falls short just reports the gap, because a handful
  of fruit closes it more cheaply than another thing to cook.
- **Prep** lists the week by cooking session, not by when you eat it.

## The data

`claudemeals.db` is deliberately not tracked — it holds your recipes and plans and changes
constantly, and binary SQLite diffs badly. To snapshot it in a form that version-controls well:

```
sqlite3 claudemeals.db .dump > data/claudemeals.sql
```

Backups live in `backups/`, also untracked. Take one before anything destructive.
