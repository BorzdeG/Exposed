package kotlin.dao

import kotlin.sql.*
import java.util.HashMap
import java.util.LinkedHashMap
import kotlin.properties.Delegates

/**
 * @author max
 */
class EntityID(val id: Int, val factory: EntityClass<*>) {
    fun table() = factory.table
}

private fun <T:Int?>checkReference(reference: Column<T>, factory: EntityClass<*>) {
    val refColumn = reference.referee
    if (refColumn == null) throw RuntimeException("Column $reference is not a reference")
    val targetTable = refColumn.table
    if (factory.table != targetTable) {
        throw RuntimeException("Column and factory point to different tables")
    }
}

class Reference<Target : Entity> (val reference: Column<Int>, val factory: EntityClass<Target>) {
    {
        checkReference(reference, factory)
    }
}

class OptionalReference<Target: Entity> (val reference: Column<Int?>, val factory: EntityClass<Target>) {
    {
        checkReference(reference, factory)
    }
}

class OptionalReferenceSureNotNull<Target: Entity> (val reference: Column<Int?>, val factory: EntityClass<Target>) {
    {
        checkReference(reference, factory)
    }
}

class Referrers<Source:Entity>(val reference: Column<Int>, val factory: EntityClass<Source>, val cache: Boolean) {
    {
        val refColumn = reference.referee
        if (refColumn == null) throw RuntimeException("Column $reference is not a reference")

        if (factory.table != reference.table) {
            throw RuntimeException("Column and factory point to different tables")
        }
    }

    fun get(o: Entity, desc: jet.PropertyMetadata): Iterable<Source> {
        val query = {factory.find(reference.eq(o.id))}
        return if (cache) EntityCache.getOrCreate(Session.get()).getOrPutReferrers(o, reference, query)  else query()
    }
}

class OptionalReferrers<Source:Entity>(val reference: Column<Int?>, val factory: EntityClass<Source>, val cache: Boolean) {
    {
        val refColumn = reference.referee
        if (refColumn == null) throw RuntimeException("Column $reference is not a reference")

        if (factory.table != reference.table) {
            throw RuntimeException("Column and factory point to different tables")
        }
    }

    fun get(o: Entity, desc: jet.PropertyMetadata): Iterable<Source> {
        val query = {factory.find(reference.eq(o.id))}
        return if (cache) EntityCache.getOrCreate(Session.get()).getOrPutReferrers(o, reference, query)  else query()
    }
}

open class ColumnWithTransform<TColumn, TReal>(val column: Column<TColumn>, val toColumn: (TReal) -> TColumn, val toReal: (TColumn) -> TReal) {
}

class View<Target: Entity> (val op : Op, val factory: EntityClass<Target>) : Iterable<Target> {
    public override fun iterator(): Iterator<Target> = factory.find(op).iterator()
    fun get(o: Any?, desc: jet.PropertyMetadata): Iterable<Target> = factory.find(op)
}

class InnerTableLink<Target: Entity>(val table: Table,
                                     val target: EntityClass<Target>) {
    fun get(o: Entity, desc: jet.PropertyMetadata): Iterable<Target> {
        val sourceRefColumn = table.columns.find { it.referee == o.factory().table.id } as? Column<Int> ?: throw RuntimeException("Table does not reference source")
        return with(Session.get()) {
            target.wrapRows(target.table.innerJoin(table).select(sourceRefColumn.eq(o.id)))
        }
    }
}

open public class Entity(val id: Int) {
    var klass: EntityClass<*>? = null

    val writeValues = LinkedHashMap<Column<*>, Any?>()
    var _readValues: ResultRow? = null
    val readValues: ResultRow
    get() {
        return _readValues ?: run {
            _readValues = with(Session.get()) {
                val table = factory().table
                table.select(table.id.eq(id))
            }.first()
            _readValues!!
        }
    }

    /*private val cachedData = LinkedHashMap<String, Any>()
    public fun<T> getOrCreate(key: String, evaluate: ()->T) : T {
        return cachedData.getOrPut(key, evaluate) as T
    }*/

    public fun factory(): EntityClass<*> = klass!!

    fun <T: Entity> Reference<T>.get(o: Entity, desc: jet.PropertyMetadata): T {
        return factory.findById(reference.get(o, desc))!!
    }

    fun <T: Entity> Reference<T>.set(o: Entity, desc: jet.PropertyMetadata, value: T) {
        reference.set(o, desc, value.id)
    }

    fun <T: Entity> OptionalReference<T>.get(o: Entity, desc: jet.PropertyMetadata): T? {
        return reference.get(o, desc)?.let{factory.findById(it)}
    }

    fun <T: Entity> OptionalReference<T>.set(o: Entity, desc: jet.PropertyMetadata, value: T?) {
        reference.set(o, desc, value?.id)
    }

    fun <T: Entity> OptionalReferenceSureNotNull<T>.get(o: Entity, desc: jet.PropertyMetadata): T {
        return reference.get(o, desc)!!.let{factory.findById(it)}!!
    }

    fun <T: Entity> OptionalReferenceSureNotNull<T>.set(o: Entity, desc: jet.PropertyMetadata, value: T) {
        reference.set(o, desc, value.id)
    }

    fun <T> Column<T>.get(o: Entity, desc: jet.PropertyMetadata): T {
        if (id == -1) {
            throw RuntimeException("Prototypes are write only")
        }
        else {
            if (writeValues.containsKey(this)) {
                return writeValues[this] as T
            }
            return readValues[this]
        }
    }

    fun <T> Column<T>.set(o: Entity, desc: jet.PropertyMetadata, value: T) {
        if (writeValues.containsKey(this) || _readValues == null || _readValues!![this] != value) {
            writeValues[this] = value
        }
    }

    fun <TColumn, TReal> ColumnWithTransform<TColumn, TReal>.get(o: Entity, desc: jet.PropertyMetadata): TReal {
        return toReal(column.get(o, desc))
    }

    fun <TColumn, TReal> ColumnWithTransform<TColumn, TReal>.set(o: Entity, desc: jet.PropertyMetadata, value: TReal) {
        column.set(o, desc, toColumn(value))
    }

    public fun <Target:Entity> EntityClass<Target>.via(table: Table): InnerTableLink<Target> {
        return InnerTableLink(table, this@via)
    }

    public fun <T: Entity> s(c: EntityClass<T>): EntityClass<T> = c

    public fun delete(){
        with(Session.get()) {
            val table = factory().table
            delete(table).where(table.id.eq(id))
        }
    }

    fun flush() {
        if (!writeValues.isEmpty()) {
            with(Session.get()) {
                val table = factory().table
                table.update(table.id.eq(id)) {
                    for ((c, v) in writeValues) {
                        it[c as Column<Any?>] = v
                    }
                }
            }

            // move write values to read values
            if (_readValues != null) {
                for ((c, v) in writeValues) {
                    _readValues!!.data.remove(c)
                    _readValues!!.data.put(c, v)
                }
            }

            // clear write values
            writeValues.clear()
        }
    }
}

class EntityCache {
    val data = HashMap<EntityClass<*>, MutableMap<Int, *>>()
    val referrers = HashMap<Entity, MutableMap<Column<*>, List<*>>>()

    private fun <T: Entity> getMap(f: EntityClass<T>) : MutableMap<Int, T> {
        val answer = data.getOrPut(f, {
            HashMap()
        }) as MutableMap<Int, T>

        return answer
    }

    fun <T: Entity, R: Entity> getOrPutReferrers(source: T, key: Column<*>, refs: ()->Iterable<R>): List<R> {
        return referrers.getOrPut(source, {HashMap()}).getOrPut(key, {refs().toList()}) as List<R>
    }

    fun <T: Entity> find(f: EntityClass<T>, id: Int): T? {
        return getMap(f)[id]
    }

    fun <T: Entity> findAll(f: EntityClass<T>): Iterable<T> {
        return getMap(f).values()
    }

    fun <T: Entity> store(f: EntityClass<T>, o: T) {
        getMap(f).put(o.id, o)
    }

    fun flush() {
        for ((f, map) in data) {
            for ((i, p) in map) {
                (p as Entity).flush()
            }
        }
    }

    class object {
        val key = Key<EntityCache>()
        val newCache = { EntityCache()}

        fun getOrCreate(s: Session): EntityCache {
            return s.getOrCreate(key, newCache)
        }
    }
}

abstract public class EntityClass<out T: Entity>(val table: IdTable, val eagerSelect: Boolean = false) {
    private val klass = javaClass.getEnclosingClass()!!
    private val ctor = klass.getConstructors()[0]

    public fun get(id: Int): T {
        return findById(id) ?: throw RuntimeException("Entity not found in database")
    }

    private fun warmCache(): EntityCache {
        val cache = EntityCache.getOrCreate(Session.get())
        if (eagerSelect) {
            if (!cache.data.containsKey(this)) {
                retrieveAll().count()
            }
        }

        return cache
    }

    public fun findById(id: Int): T? {
        return warmCache().find(this, id) ?: find(table.id.eq(id)).firstOrNull()
    }

    public fun wrapRows(rows: Iterable<ResultRow>): Iterable<T> {
        val session = Session.get()
        return rows mapLazy {
            wrapRow(it, session)
        }
    }

    public fun wrapRow (row: ResultRow, session: Session) : T {
        val entity = wrap(row[table.id], row, session)
        entity._readValues = row
        return entity
    }

    public fun all(): Iterable<T> {
        if (eagerSelect) {
            return warmCache().findAll(this)
        }

        return retrieveAll()
    }

    private fun retrieveAll(): Iterable<T> {
        return with(Session.get()) {
            wrapRows(table.selectAll())
        }
    }

    public fun find(op: Op): Iterable<T> {
        return with (Session.get()) {
            wrapRows(searchQuery(op))
        }
    }

    protected open fun Session.searchQuery(op: Op): Query {
        return table.select(op)
    }

    public fun count(op: Op? = null): Int {
        return with (Session.get()) {
            val query = table.slice(count(table.id))
            (if (op == null) query.selectAll() else query.select(op)).first()[
                count(table.id)
            ]
        }
    }

    protected open fun createInstance(entityId: Int, row: ResultRow?) : T = ctor.newInstance(entityId) as T

    public fun wrap(id: Int, row: ResultRow?, s: Session): T {
        val cache = EntityCache.getOrCreate(s)
        return cache.find(this, id) ?: run {
            val new = createInstance(id, row)
            new.klass = this
            cache.store(this, new)
            new
        }
    }

    public fun new(prototype: T = createInstance(-1, null), init: T.() -> Unit) : T {
        prototype.init()

        val row = InsertQuery(table)
        for ((c, v) in prototype.writeValues) {
            row.set(c as Column<Any?>, v)
        }

        val session = Session.get()
        row.execute(session)
        return wrap(row get table.id, null, session)
    }

    public fun view (op: Op) : View<T>  = View(op, this)

    public fun referencedOn(column: Column<Int>): Reference<T> {
        return Reference(column, this)
    }

    public fun optionalReferencedOn(column: Column<Int?>): OptionalReference<T> {
        return OptionalReference(column, this)
    }

    public fun optionalReferencedOnSureNotNull(column: Column<Int?>): OptionalReferenceSureNotNull<T> {
        return OptionalReferenceSureNotNull(column, this)
    }

    public fun referrersOn(column: Column<Int>, cache: Boolean = false): Referrers<T> {
        return Referrers(column, this, cache)
    }

    //TODO: what's the difference with referrersOn?
    public fun optionalReferrersOn(column: Column<Int?>, cache: Boolean = false): OptionalReferrers<T> {
        return OptionalReferrers(column, this, cache)
    }

    fun<TColumn: Any?,TReal: Any?> Column<TColumn>.transform(toColumn: (TReal) -> TColumn, toReal: (TColumn) -> TReal): ColumnWithTransform<TColumn, TReal> {
        return ColumnWithTransform(this, toColumn, toReal)
    }
}