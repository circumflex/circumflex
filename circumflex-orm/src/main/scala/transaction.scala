package ru.circumflex.orm

import java.sql.{PreparedStatement, Connection}
import collection.mutable.HashMap
import ORM._

// ## Transaction management

// ### Transaction demarcation

// *Transaction demarcation* refers to setting the transaction boundaries.
//
// Datatabase transaction boundaries are always necessary. No communication with the
// database can occur outside of a database transaction (this seems to confuse many
// developers who are used to the auto-commit mode). Always use clear transaction
// boundaries, even for read-only operations. Depending on your isolation level and
// database capabilities this might not be required but there is no downside if you
// always demarcate transactions explicitly.
//
// There are several popular transaction demarcation patterns for various application types,
// most of which operate with some sort of "context" or "scope", to which a single
// transaction corresponds. For example, in web applications a transaction may correspond
// to a single request.

/**
 * ### TransactionManager interface
 *
 * *Transaction manager* aims to help developers demarcate their transactions
 * by providing contextual *current* transaction. By default it uses `ThreadLocal`
 * to bind contextual transactions (a separate transaction is allocated for each thread,
 * and each thread works with one transaction at a given time). You can
 * provide your own transaction manager by implementing the `TransactionManager`
 * trait and setting the `orm.transactionManager` configuration parameter.</p>
 *
 * Defines a contract to open stateful transactions and return
 * thread-locally current transaction.
 */
trait TransactionManager {
  private val threadLocalContext = new ThreadLocal[StatefulTransaction]

  /**
   * Does transaction manager has live current transaction?
   */
  def hasLiveTransaction_?(): Boolean =
    threadLocalContext.get != null && threadLocalContext.get.live_?

  /**
   * Retrieve a contextual transaction.
   */
  def getTransaction: StatefulTransaction = {
    if (!hasLiveTransaction_?) threadLocalContext.set(openTransaction)
    return threadLocalContext.get
  }

  /**
   * Sets a contextual transaction to specified `tx`.
   */
  def setTransaction(tx: StatefulTransaction): Unit =threadLocalContext.set(tx)

  /**
   * Open new stateful transaction.
   */
  def openTransaction(): StatefulTransaction = new StatefulTransaction()

  /**
   * Execute specified `block` in specified `transaction` context and
   * commits the `transaction` afterwards.
   *
   * If any exception occur, rollback the transaction and rethrow an
   * exception.
   *
   * The contextual transaction is replaced with specified `transaction` and
   * is restored after the execution of `block`.
   */
  def executeInContext(transaction: StatefulTransaction)(block: => Unit) = {
    val prevTx: StatefulTransaction = if (hasLiveTransaction_?) getTransaction else null
    try {
      setTransaction(transaction)
      block
      if (transaction.live_?) {
        transaction.commit
        ormLog.debug("Committed current transaction.")
      }
    } catch {
      case e =>
        if (transaction.live_?) {
          transaction.rollback
          ormLog.error("Rolled back current transaction.")
        }
        throw e
    } finally if (transaction.live_?) {
      transaction.close
      ormLog.debug("Closed current connection.")
      setTransaction(prevTx)
    }
  }
}

object DefaultTransactionManager extends TransactionManager

// ### Stateful Transactions

/**
 * The point to use extra-layer above standard JDBC connections is to maintain
 * a cache for each transaction.
 */
class StatefulTransaction {

  /**
   * Undelying JDBC connection.
   */
  val connection: Connection = ORM.connectionProvider.openConnection

  /**
   * Should underlying connection be closed on `commit` or `rollback`?
   */
  protected var autoClose = false

  def setAutoClose(value: Boolean): this.type = {
    this.autoClose = value
    return this
  }

  def autoClose_?(): Boolean = this.autoClose

  /**
   * Is underlying connection alive?
   */
  def live_?(): Boolean = connection != null && !connection.isClosed

  /**
   * Commit the transaction (and close underlying connection if `autoClose` is set to `true`).
   */
  def commit(): Unit = try {
    if (!live_?) return
    connection.commit
  } finally {
    if (autoClose) close()
  }

  /**
   * Rollback the transaction (and close underlying connection if `autoClose` is set to `true`).
   */
  def rollback(): Unit = try {
    if (!live_?) return
    connection.rollback
  } finally {
    if (autoClose) connection.close
  }

  /**
   * Close the underlying connection and dispose of any resources associated with this
   * transaction.
   */
  def close(): Unit =
    if (!live_?) return
    else connection.close()

  /**
   * Executes a statement in current transaction.
   */
  def execute[A](sql: String)(actions: PreparedStatement => A): A = {
    val st = connection.prepareStatement(sql)
    try {
      return actions(st)
    } finally {
      st.close
    }
  }
  def execute[A](actions: Connection => A): A = actions(connection)

}
