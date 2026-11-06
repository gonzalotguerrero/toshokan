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
| `members`                         | `member_id`                                       | A member by id                        |
| `members_by_card_number`          | `card_number`                                     | Card uniqueness, and the member behind a card |
| `member_directory`                | one partition → `sort_name`, `member_id`          | The member list, by name              |
| `loans_by_copy`                   | `book_copy_id` → `lent_at` desc, `loan_id`        | A copy's loan history                 |
| `loans_by_member`                 | `member_id` → `lent_at` desc, `loan_id`           | A member's loan history               |
| `book_locks`                      | `book_id`                                         | The per-book lock                     |
| `member_lending_locks`            | `member_id`                                       | The per-member lock                   |

`book_copies_by_book` also has secondary indexes on `member_id` (a member's current loans) and `status` (the
overdue list). The holdings and the member directory each sit in a single partition. That's fine for one
library, and it's what lets them be listed in order.

## Keeping the data consistent

The rule is that no row's correctness may depend on a later write that might never happen.

- **Uniqueness goes through conditional writes.** ISBNs, inventory numbers and card numbers are claimed with
  `IF NOT EXISTS` before anything else is written, and released only by their owner if the rest fails. A new
  book can take over an ISBN claim whose book doesn't exist, as long as the claim is more than 10 minutes old.
  A younger claim may belong to a create that's still in progress.
- **Rows that repeat other rows are written together.** A book with its title and author rows, a member with
  their directory entry, and the two loan history tables are each written in one logged batch, so either all
  the rows are applied or none are.
- **A copy is the truth about its own loan.** Its status and current loan always change together, in one
  conditional `UPDATE ... IF`. Every write to `book_copies_by_book` is conditional, because conditional and
  plain writes are timestamped by different clocks, and mixing them on one row can lose updates.
- **Short locks for rules that span rows.** "At most 3 books per member" can't be checked on a single copy, so
  lending and deactivating a member take a per-member lock. Deleting a book, adding a copy, and putting a copy
  back from withdrawn take a per-book lock. Locks are `IF NOT EXISTS` rows with a 10-second TTL, so a lock
  that never gets released clears itself.

### Loan history

A loan goes into the history before the copy changes, so the history never misses a loan. The downside is that
a request that fails halfway leaves a row for a lend or return that didn't happen. So every row is checked
against the copy when it's read:

- If the row is the copy's current loan, the loan is active, and its due date and renewals come from the copy.
- Otherwise, if the row has a return date, the loan was returned.
- Otherwise, the row comes from a lend that never went through, and it's left out.

Rows left out this way are why a history page can have fewer items than asked for.

### The exception: holdings

The counts behind `GET /books` are recounted from a book's copies every time one of them changes. If a recount
fails, the counts stay wrong until the next change to that book's copies. This is a deliberate trade-off that
keeps every copy change to a single conditional write.
