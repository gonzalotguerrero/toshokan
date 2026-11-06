# API guide

All paths are relative to `http://localhost:8080/toshokan/api/v1`. With the app running, the same endpoints can be
browsed and tried out in Swagger UI at http://localhost:8080/toshokan/swagger-ui.html.

## Books

| Method   | Path                                          | What it does                                                    |
|----------|-----------------------------------------------|-----------------------------------------------------------------|
| `POST`   | `/books`                                      | Adds a title                                                    |
| `GET`    | `/books/{bookId}`                             | Fetches one title                                               |
| `GET`    | `/books/search?isbn=` / `author=` / `title=`  | Finds titles by exactly one of the three (paged)                |
| `DELETE` | `/books/{bookId}`                             | Deletes a title, if it has no copies or all of them are withdrawn |

A book needs a title, at least one author, a publication year, a language and at least one genre. ISBN and
publisher are optional, and no two books can share an ISBN. Books can't be edited once they're created.

Searching by author or title matches the whole value, but ignores case, extra spaces and full-width vs.
half-width characters. "dune" finds "Dune", but "Dune" doesn't find "Dune Messiah".

## Copies and holdings

| Method   | Path                                   | What it does                                                         |
|----------|----------------------------------------|----------------------------------------------------------------------|
| `GET`    | `/books?available=`                    | Every title the library has copies of, by title (paged). With `available=true`, only titles with a copy on the shelf |
| `POST`   | `/books/{bookId}/copies`               | Adds a copy, with the inventory number from its label                |
| `GET`    | `/books/{bookId}/copies`               | Every copy of a title, withdrawn ones included                       |
| `PATCH`  | `/books/{bookId}/copies/{bookCopyId}`  | Changes a copy's status by hand, e.g. `{"status": "DAMAGED"}`        |
| `DELETE` | `/books/{bookId}/copies/{bookCopyId}`  | Withdraws the copy. Its record stays                                 |

A copy is `AVAILABLE`, `LENT`, `LOST`, `DAMAGED`, `IN_REPAIR` or `WITHDRAWN`. Only lending and returning can set
or clear `LENT`.

## Members

| Method   | Path                    | What it does                                                    |
|----------|-------------------------|-----------------------------------------------------------------|
| `POST`   | `/members`              | Registers a member: card number, full name, and an email or phone (or both) |
| `GET`    | `/members/{memberId}`   | Fetches one member                                              |
| `GET`    | `/members?cardNumber=`  | Lists members by name (paged), or finds one by card number      |
| `PATCH`  | `/members/{memberId}`   | Changes the name, contact details or status                     |
| `DELETE` | `/members/{memberId}`   | Deactivates the member. Refused while they have books out       |

## Lending desk

The desk works with the numbers a librarian scans: the inventory number on the book's label and the member's
card number.

| Method | Path                                               | What it does                                    |
|--------|----------------------------------------------------|-------------------------------------------------|
| `POST` | `/loans`                                           | Lends a copy: `{"inventoryNumber": "...", "cardNumber": "..."}` |
| `POST` | `/returns`                                         | Takes a copy back: `{"inventoryNumber": "..."}` |
| `POST` | `/renewals`                                        | Renews a loan: `{"inventoryNumber": "..."}`     |
| `GET`  | `/members/{memberId}/loans`                        | What a member has out now, due soonest first    |
| `GET`  | `/loans/overdue`                                   | Every late loan, most overdue first, with the member's contact details |
| `GET`  | `/members/{memberId}/loan-history`                 | A member's loans, newest first (paged)          |
| `GET`  | `/books/{bookId}/copies/{bookCopyId}/loan-history` | A copy's loans, newest first (paged)            |

The rules:

- Only available copies can be lent.
- A member can't borrow while inactive, while they have an overdue book, or once they're at the loan limit.
- Renewing sets the due date to today plus the loan period. Overdue loans can't be renewed, only returned.
- A loan is overdue from the day after its due date.

The limits come from the [configuration](../README.md#configuration).

## Paging

Paged lists take `pageSize` (1 to 100, default 20) and `pageToken`. Leave out `pageToken` for the first page,
then send the `nextPageToken` from the previous page:

```json
{
  "items": [],
  "nextPageToken": "AAAAEAEAA..."
}
```

`nextPageToken` is `null` on the last page. Loan history, member list and `GET /books` pages can have fewer
items than `pageSize` even when more pages follow (the [design notes](design.md#keeping-the-data-consistent) explain why).

## Errors

Errors come back as [Problem Details](https://www.rfc-editor.org/rfc/rfc9457):

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "Member CARD-0001 already has 3 books out, which is the most allowed"
}
```

| Status | When                                                                  |
|--------|-----------------------------------------------------------------------|
| 400    | Missing or invalid fields, a malformed ISBN, a bad page token. Field problems are listed in `errors` |
| 404    | The book, copy or member doesn't exist                                |
| 409    | A rule says no: a duplicate ISBN, a copy that's already out, a book that still has copies |

A 409 that says something is being changed "at the same time" or "right now" means another request got there
first. Trying again is safe.

A 500 from a create (book, copy or member) can mean Cassandra timed out and the write may still land. The
number it claimed stays taken until either the record appears or 10 minutes pass, so retrying straight away can
answer 409 "already in use".

## Walkthrough

This adds a book and a copy, registers a member, and takes the copy through a loan.

```shell
API=http://localhost:8080/toshokan/api/v1

# Add a title, and copy the bookId from the response
curl -s -X POST $API/books -H 'Content-Type: application/json' -d '{
  "isbn": "978-0-441-01359-3", "title": "Dune", "authors": ["Frank Herbert"],
  "publicationYear": 1965, "language": "English", "genres": ["Science fiction"]}'
BOOK_ID=...

# Put a copy on the shelf
curl -s -X POST $API/books/$BOOK_ID/copies -H 'Content-Type: application/json' \
  -d '{"inventoryNumber": "LIB-000001"}'

# Register a member, and copy the memberId from the response
curl -s -X POST $API/members -H 'Content-Type: application/json' \
  -d '{"cardNumber": "CARD-0001", "fullName": "Ana Pérez", "email": "ana@example.com"}'
MEMBER_ID=...

# Lend, renew and return the copy
curl -s -X POST $API/loans -H 'Content-Type: application/json' \
  -d '{"inventoryNumber": "LIB-000001", "cardNumber": "CARD-0001"}'
curl -s -X POST $API/renewals -H 'Content-Type: application/json' -d '{"inventoryNumber": "LIB-000001"}'
curl -s -X POST $API/returns -H 'Content-Type: application/json' -d '{"inventoryNumber": "LIB-000001"}'

# The loan is now in the member's history, with its return date
curl -s $API/members/$MEMBER_ID/loan-history
```
