# Design notes

## Modules

Dependencies only point one way: inventory uses the catalog, and circulation uses inventory, members and the
catalog. When a module further down needs a say in something, it defines an interface and a module above
implements it. Members can't know about loans, for example, so it asks a `MemberDeactivationGuard` (implemented
by circulation) whether a member can be deactivated. Deleting a book goes through `BookDeletionGuard` the same
way, implemented by inventory.

## Tables

Each Cassandra table is designed around a query, so the same data often lives in more than one table.

| Table                             | Key (partition → clustering)                      | Answers                               |
|-----------------------------------|---------------------------------------------------|---------------------------------------|
| `books`                           | `book_id`                                         | A book by id                          |
| `books_by_isbn`                   | `isbn`                                            | ISBN uniqueness, and search by ISBN   |
| `books_by_title`                  | `title_key` → `publication_year` desc, `book_id`  | Search by title                       |
| `books_by_author`                 | `author_key` → `title_key`, `book_id`             | Search by author                      |
| `book_copies_by_book`             | `book_id` → `book_copy_id`                        | A title's copies and their current loans |
| `book_copies_by_inventory_number` | `inventory_number`                                | Number uniqueness, and the copy behind a label |
| `book_holdings`                   | one partition → `sort_title`, `book_id`           | `GET /books`                          |
| `members`                         | `member_id`                                       | A member by id, with their loan slots |
| `members_by_card_number`          | `card_number`                                     | Card uniqueness, and the member behind a card |
| `member_directory`                | one partition → `sort_name`, `member_id`          | The order of the member list, by name |
| `loans_by_copy`                   | `book_copy_id` → `lent_at` desc, `loan_id`        | A copy's loan history                 |
| `loans_by_member`                 | `member_id` → `lent_at` desc, `loan_id`           | A member's loan history               |
| `book_locks`                      | `book_id`                                         | The per-book lock                     |

`book_copies_by_book` also has secondary indexes on `member_id` (a member's current loans) and `status` (the
overdue list). The holdings and the member directory each sit in a single partition. That's fine for one
library, and it's what lets them be listed in order.

## Keeping the data consistent

The rule is that no row's correctness may depend on a later write that might never happen.

- **Uniqueness goes through conditional writes.** ISBNs, inventory numbers and card numbers are claimed with
  `IF NOT EXISTS` before anything else is written.
  - If saving the owner then fails, the claim is released only when the write certainly didn't happen (for
    example, too few replicas were up). After a timeout or a lost connection the write may still land, so the
    claim is kept (see `WriteOutcomes`).
  - A claim whose owner doesn't exist can be taken over by a new owner once it's more than 10 minutes old (see
    `StaleClaims`). A younger claim may belong to a create that's still in progress.
  - Releasing and taking over are both conditional on the current owner, so neither can touch a claim that has
    already moved on.
- **Rows that repeat other rows are written together.** A book with its title and author rows, and the two loan
  history tables, are each written in one logged batch, so either all the rows are applied or none are.
- **A member changes with one conditional write.** Every write to `members` is conditional: new members use
  `IF NOT EXISTS`, and changes use `IF version = ?` with the version that was read. If someone else changed the
  member in between, the request gets a 409 instead of overwriting their change.
- **A copy is the truth about its own loan.** Its status and current loan always change together, in one
  conditional `UPDATE ... IF`. Every write to `book_copies_by_book` is conditional, because conditional and
  plain writes are timestamped by different clocks, and mixing them on one row can lose updates.
- **The member decides the member rules.** "At most 3 books per member" can't be checked on any one copy, so
  every lend first reserves a loan slot on the member with `IF version = ?` (see [Loan slots](#loan-slots)).
  Deactivating a member is the same kind of write, so a lend and a deactivation can't both get through.
- **A short lock for deleting a book.** Deleting a book, adding a copy, and putting a copy back from withdrawn
  take a per-book lock (see [The book lock](#the-book-lock)).

### Loan history

A loan goes into the history before the copy changes, so the history never misses a loan. The downside is that
a request that fails halfway leaves a row for a lend or return that didn't happen. So every row is checked
against the copy when it's read:

- If the row is the copy's current loan, the loan is active, and its due date and renewals come from the copy.
- Otherwise, if the row has a return date, the loan was returned.
- Otherwise, the row comes from a lend that never went through, and it's left out.

Rows left out this way are why a history page can have fewer items than asked for.

### Loan slots

`members.loan_slots` maps a loan id to the time it was reserved. A lend goes like this:

1. Read the member, then the copies lent to them. The order matters: a lend that reserves a slot after the read
   changes the version, so this one's reservation fails.
2. Count what's taken: every copy lent to the member, plus the slots that are still pending.
3. Check the rules (active, nothing overdue, under the limit), then reserve a slot with `IF version = ?`. The new
   slot list keeps only the pending slots, so finished ones are dropped along the way.
4. Write the loan history, then lend the copy.

A slot is pending unless a copy shows its loan (it already counts through the copy), the history says the loan was
returned, or it's more than 10 minutes old (a lend whose copy write hasn't landed by then never will). If the lend
certainly failed, its slot is removed straight away, with `IF version = ?` for the version the reservation wrote
(so a lend that read the member in between can't write the slot back). If the member changed meanwhile, or after a
timeout, when the copy may still end up lent, the slot is kept and stops counting once it's 10 minutes old. Returns and renewals don't touch the member at all.

Any change to a member also changes the version, so a lend that overlaps with, say, an email change gets a 409 and
has to be retried.

### The book lock

The lock is an `IF NOT EXISTS` row with a 10-second TTL, so a lock that never gets released clears itself. The
TTL also means a request that stalls (a long GC pause, a slow Cassandra) can lose the lock without knowing it. To
make that unlikely, the write that decides the outcome only starts if less than 5 seconds have passed since the
lock was taken, measured with a monotonic clock so a change to the server's time can't fool it; otherwise the
request gets a 409 and nothing is saved. This narrows the gap but doesn't close it:
a pause between that check and the write could still let a second request in. Deletes are rare and done by staff,
so this was judged good enough.

### Member directory

A directory entry can't share a batch with the member's conditional write, so it's written just before it. An
entry on its own proves nothing, so the member list checks each one against the member:

- An entry is shown only if the member exists and their current name gives the entry's `sort_name`.
- What's shown (name, contact details, status) always comes from `members`.

Entries left behind by a rename, by a change that lost a race, or by a sign-up that failed are skipped. Renaming
never deletes the old entry: the delete could arrive late and remove the entry the member needs again after
renaming back. As with loan history, skipped entries mean a page can have fewer items than asked for.

### The exception: holdings

The counts behind `GET /books` are recounted from a book's copies every time one of them changes. If a recount
fails, the counts stay wrong until the next change to that book's copies. This is a deliberate trade-off that
keeps every copy change to a single conditional write.

A deleted book's copies never change again, so a recount that failed during the delete would never be fixed. To
cover that, the listing checks each page's rows against `books` and leaves out titles that no longer exist.
