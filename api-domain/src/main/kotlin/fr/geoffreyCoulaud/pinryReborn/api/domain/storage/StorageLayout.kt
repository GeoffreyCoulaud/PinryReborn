package fr.geoffreyCoulaud.pinryReborn.api.domain.storage

/**
 * The directory segments every data directory shares, so the stores, the keys derived without a
 * row, the orphan sweep and the boot check all resolve the same names.
 */
object StorageLayout {
    /** Where a store stages a file before promoting it, under its own data directory. */
    const val STAGING_DIRECTORY = "tmp"

    /** Where promoted export archives live, under `exports.data_dir`. */
    const val EXPORTS_DIRECTORY = "exports"

    /** Where promoted import archives live, under `imports.data_dir`. */
    const val IMPORTS_DIRECTORY = "imports"
}
