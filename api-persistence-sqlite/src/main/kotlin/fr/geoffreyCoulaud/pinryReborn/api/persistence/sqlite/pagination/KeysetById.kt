package fr.geoffreyCoulaud.pinryReborn.api.persistence.sqlite.pagination

import io.ebean.typequery.QueryBean
import java.util.UUID

/**
 * One page of a sweep selection by `id`: the rows after [afterId], `null` for the first page, at most
 * [limit] of them. A row a page acts on leaves the predicate; one it fails on is behind the cursor.
 */
internal fun <M, Q : QueryBean<M, Q>> Q.pageByIdAfter(afterId: UUID?, limit: Int): Q {
    if (afterId != null) raw("id > ?", afterId)
    return orderBy("id asc").setMaxRows(limit)
}
