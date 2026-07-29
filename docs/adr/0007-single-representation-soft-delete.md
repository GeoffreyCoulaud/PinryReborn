# 0007. One representation of soft deletion, filtered explicitly

Status: Accepted
Date: 2026-07-29
Amends: `docs/adr/0006-domain-owned-timestamps.md`, whose decision 4 this reverses. Its eleven other
decisions stand.

## Context

Block 1 of `docs/adr/0006-domain-owned-timestamps.md` had not started: the branch carried documents
only, no production change. Planning it required settling five questions about Ebean's `@SoftDelete`,
none of which the documentation answers usefully. They were answered in Ebean 19.2.0's own sources (the
sources jar from Maven Central, since no 19.2.0 tag exists) and in this project's enhanced bytecode.

| # | Question | Answer, and where it was found |
|---|---|---|
| 1 | Where does the automatic predicate land? | WHERE clause for the **root** table only. For every joined table it goes in the JOIN's ON condition (`SqlTreeNodeBean.appendFromBaseTable:316-320`, `SqlTreeNodeExtraJoin:164-167`, `SqlTreeNodeRoot:65`, `CQueryBuilder$BuildReq.appendSoftDelete:744`). In a left join that nulls the association instead of dropping the row. **The documentation states the opposite**, showing both predicates in the WHERE clause |
| 2 | Does a foreign-key filter carry the parent's predicate? | No. `.assoc.id` is a `BeanFkeyProperty`, "Used to evaluate imported foreign keys so as to avoid unnecessary joins", with a null `elPrefix`, and only a non-null prefix records a join (`ImportedIdSimple.addFkeys:90`, `DeployPropertyParser.addIncludes`) |
| 3 | What does a delete query do on a soft-delete type? | Two compilation paths selected by `BeanDescriptor.isDeleteByStatement:1311`, which tests for cascade associations, an L2 cache and persist listeners, not for soft delete. The root's own predicate is never added to a delete query. On the single-statement path nothing consults the `permanent` flag, so read literally `delete()` hard-deletes a type with no cascade, no cache and no listener. No upstream test covers that case |
| 4 | Can `merge` write over a soft-deleted row? | No. `MergeHandler.fetchOutline:84-97` queries without `setIncludeSoftDeletes()`, so the outline comes back null and an insert is attempted, colliding on the primary key. `ModelRepository.saveAndReturn:13` is `merge`, so `savePin`, `saveBoard` and `saveUser` all take that path |
| 5 | Does a derived setter survive enhancement? | Yes for writes, and load bypasses it (`MethodFieldAdapter` rewrites field access inside method bodies; `BeanProperty.setValue:707` is "without interception"). But `BeanProperty.setSoftDeleteValue` writes the flag through the non-intercepting path and the by-id soft delete is raw SQL, so **Ebean's own deletion bypasses any derivation** and would leave `softDeletedAt` null on an invisible row |

Two factual claims in `0006` are refuted by fact 2. Its context states that "26 `.author.` / `.user.`
joins currently depend on that filtering without saying so": all 26 are `.id` navigations (15
`.author.id`, 11 `.user.id`, the whole population), which join nothing and filter nothing, today or
after. And its decision 4 rejects the alternative because "no helper can reach the joins": there are no
joins to reach. The real guard on the authentication path is `SessionTokenRepository.kt:22`, a direct
`QUserModel()` access where `users` is the root table, which `0006` lists among the joins.

**The underlying problem is not that `@SoftDelete` has pitfalls.** It is that a business rule, "a
recycled entity is not visible", is carried by an implicit effect whose conditions of application are
not readable at the call site. `QPinModel().author.id.equalTo(x).findList()` may or may not exclude
recycled pins, and nothing in that line says which. The answer depends on the root table, on whether a
join exists, on whether the navigation is an id, on the kind of operation, and on the entity's cascade
and caching configuration. The cost of this session is the symptom: three plan revisions, two
excursions into an ORM's sources, an official documentation page wrong on its central example, and two
behaviours no upstream test covers, all before the first line of production code.

A second, plainer problem feeds the first: **two columns say the same thing**. `softDeletedAt` belongs
to the domain, `deleted` to the framework, and the machinery block 1 had grown (a mapped superclass, a
derived setter, a Konsist ban on `database.delete(`) existed only to keep them in step. That work
produces no business value.

## Decision

1. **One representation. `@SoftDelete` is removed**, from `UserModel` where it exists today and from
   the plan for `PinModel` and `BoardModel`. `softDeletedAt: Instant?` is the only representation of
   recycling, in the domain and in the store. The `users.deleted` column is dropped.

2. **Filtering is explicit, and lives behind query constructors named by intention.** One object per
   recyclable type in `persistence/sqlite/queries/`, each exposing `active()`, `recycled()` and
   `any()`, which are the only places a `QPinModel`, `QBoardModel` or `QUserModel` is constructed.
   Callers read `PinQueries.active().author.id.equalTo(x)`, where the intention is in the text.

3. **Being recyclable is declared in the code**, by implementing a `SoftDeletableModel` marker
   interface, and every structural rule derives its reach from that interface. The alternative was a
   list of three type names living in the rules, where a fourth recyclable model would have arrived
   with nothing to remind anyone it needed protecting: the guard and the code could drift apart
   silently, which is the very failure this ADR is about. After this, adding a recyclable model means
   implementing the interface, and the build refuses it until its query constructors exist.

4. **The invariant is a test, not a discipline, and each half is asserted by the tool that can see
   it.** The prohibition about statements ("do not write this predicate there") needs a syntax tree,
   which is not what Konsist reads: expressed there it would match spellings, catching
   `softDeletedAt.isNull` and missing `softDeletedAt.equalTo(null)`, so its reach would be narrower
   than the rule it stands for. The project therefore gains a `detekt-rules` module (detekt 2.0's
   `dev.detekt:detekt-api`, a `RuleSetProvider` registered through the service loader). The other half,
   deriving a set of types from a marker interface and checking each has its constructors, needs a view
   of every declaration at once, which is exactly what Konsist gives and detekt does not. Neither tool
   does the other's half, and between them no rule names a type. The cost is one module of
   infrastructure, paid once; what it buys beyond this block is the ability to state future structural
   rules about code rather than about text.

5. **Recycling is a state, not a type, and one table is the right answer to it.** Two alternatives
   would have made the wrong query impossible by construction rather than by rule, and both are
   rejected on the same ground. Splitting the domain into `Pin` and `RecycledPin` would let the
   compiler refuse a recycled row in a `List<Pin>`, but it invents a distinction the business does not
   make: there are pins, and some of them are in a recycle bin. That is a state an entity is in, not a
   second kind of entity, and encoding it as a type would push a query-filtering concern up into the
   model the domain owns, to buy a guarantee the adapter is responsible for. Moving recycled rows to a
   separate table would do the same thing one layer down, and would trade a filtering mistake for a
   referential-integrity one, since `pin_board_model.pin_id` references `pins` with
   `on delete restrict`. Using a single table is the persistence layer's own and legitimate choice; the
   consequence of that choice is a filter, and it is settled where the choice was made. This closes the
   question rather than deferring it: the guarantee stays a build-time prohibition, not a compile-time
   impossibility, and that is deliberate.

6. **This reverses decision 4 of `0006`**, which chose to generalise `@SoftDelete` rather than remove
   it. That decision was taken "on measurement", and the measurement was wrong: the automatic predicate
   was credited with covering 26 navigations it never touched. Corrected, the trade-off inverts. What
   the automatic mechanism actually covers is queries rooted on the soft-delete type, an enumerable set
   that a query constructor covers just as well while saying so out loud.

7. **The rest of `0006` stands**: instants are stamped by use cases and never by the adapter,
   `updatedAt` moves on any modification including recycling and restoration, the transitions take
   their instant rather than routing through `savePin`/`saveBoard`, `User.softDeleted` becomes
   `User.softDeletedAt`, a column read by the business becomes a named domain fact, and blocks 2 and 3
   are untouched. Only the mechanism changes, not the goal.

## Consequences

- **Five researched facts become irrelevant.** Predicate placement, foreign-key navigation, the
  `delete()` ambiguity, the `merge` collision and the enhancement of a derived setter all stop mattering
  the moment no query filters implicitly. They are recorded above because they were expensive to
  establish and because they bear on any future use of Ebean's soft delete.
- **The machinery disappears**: no mapped superclass, no derived setter, no synchronisation between two
  columns, no ban on `database.delete(`, and `deletePermanent()` is no longer needed since `delete()`
  becomes a plain delete again.
- **The number of places an error is possible falls from 38 to 9.** Those three query beans are
  constructed at 38 sites across 6 files today; afterwards, at 9 sites in 3 files, each directly
  testable.
- **The authentication path changes shape and must be handled with care.** Today a tombstoned account
  cannot obtain a session because `SessionTokenRepository.kt:22` queries `QUserModel()` and Ebean adds
  the predicate. Afterwards it reads `UserQueries.active()`. The guard becomes visible instead of
  automatic, and it is covered by an existing end-to-end test.
- **Association filters are covered too, by extension rather than by constructor.** Queries rooted on a
  join table (`PinRepository.kt:62, 126`, `BoardRepository.kt:83`) cannot use a query constructor, since
  their root is not a recyclable type. They call `withActiveBoard()` / `withActivePin()`, extensions
  declared beside the constructors, and a fourth assertion forbids `softDeletedAt.isNull` and
  `softDeletedAt.isNotNull` outside that package. Those two spellings are exactly the state predicates
  Ebean's query beans offer (8 and 5 occurrences today, the whole population); writing the property,
  passing it and ordering by it are different acts and are untouched. The result is that **no way of
  expressing "is it recycled" survives outside the package that owns the answer**, which is what the
  automatic mechanism promised and did not deliver.
- **A migration drops `users.deleted`.** The history is append-only until the beta flattening, so this
  adds one entry. No backfill: `soft_deleted_at` is written from the domain and nothing is deployed.
- **`0006` is marked `Partially superseded by 0007 (decision 4)`.** The regime provides only
  `Superseded by`, which would overstate it: eleven of its twelve decisions remain in force. Recording
  the scope in the field is the honest reading of a rule whose purpose is that a reader arriving on the
  old document is not misled.
