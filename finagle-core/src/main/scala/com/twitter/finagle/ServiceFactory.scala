package com.twitter.finagle

import com.twitter.util.{Closable, Future, Time}

abstract class ServiceFactory[-Req, +Rep]
  extends (ClientConnection => Future[Service[Req, Rep]])
    with Closable { self =>

  /**
   * Reserve the use of a given service instance. This pins the
   * underlying channel and the returned service has exclusive use of
   * its underlying connection. To relinquish the use of the reserved
   * [[Service]], the user must call [[Service.close()]].
   */
  def apply(conn: ClientConnection): Future[Service[Req, Rep]]

  /**
   * Reserve the use of a given service instance using [[ClientConnection.nil]].
   * This pins the underlying resources and the returned service has exclusive use
   * of its underlying connection. To relinquish the use of the reserved
   * [[Service]], the user must call [[Service.close()]].
   */
  final def apply(): Future[Service[Req, Rep]] = this(ClientConnection.nil)

  /**
   * Apply `f` on created services, returning the resulting Future in their
   * stead. This is useful for implementing common factory wrappers that
   * only need to modify or operate on the underlying service.
   */
  def flatMap[Req1, Rep1](
    f: Service[Req, Rep] => Future[Service[Req1, Rep1]]
  ): ServiceFactory[Req1, Rep1] =
    new ServiceFactory[Req1, Rep1] {
      def apply(conn: ClientConnection): Future[Service[Req1, Rep1]] =
        self(conn) flatMap { service =>
          f(service) onFailure { _ =>
            service.close()
          }
        }
      def close(deadline: Time): Future[Unit] = self.close(deadline)
      override def status: Status = self.status
      override def toString(): String = self.toString()
    }

  /**
   * Map created services. Useful for implementing common
   * styles of factory wrappers.
   */
  def map[Req1, Rep1](f: Service[Req, Rep] => Service[Req1, Rep1]): ServiceFactory[Req1, Rep1] =
    flatMap { s =>
      Future.value(f(s))
    }

  /**
   * Make a service that after dispatching a request on that service,
   * releases the service.
   */
  final def toService: Service[Req, Rep] = new FactoryToService(this)

  /**
   * The current availability [[Status]] of this ServiceFactory
   */
  def status: Status = Status.Open

  /**
   * Return `true` if and only if [[status]] is currently [[Status.Open]].
   */
  final def isAvailable: Boolean = status == Status.Open
}

object ServiceFactory {

  /**
   * @see [[constant]] for a Java compatible API.
   */
  def const[Req, Rep](service: Service[Req, Rep]): ServiceFactory[Req, Rep] =
    new ServiceFactory[Req, Rep] {
      private[this] val noRelease = Future.value(new ServiceProxy[Req, Rep](service) {
        // close() is meaningless on connectionless services.
        override def close(deadline: Time): Future[Unit] = Future.Done
      })

      def apply(conn: ClientConnection): Future[Service[Req, Rep]] = noRelease
      def close(deadline: Time): Future[Unit] = Future.Done
    }

  /** Java compatible API for [[const]] as `const` is a reserved word in Java */
  def constant[Req, Rep](service: Service[Req, Rep]): ServiceFactory[Req, Rep] =
    ServiceFactory.const(service)

  def apply[Req, Rep](f: () => Future[Service[Req, Rep]]): ServiceFactory[Req, Rep] =
    new ServiceFactory[Req, Rep] {
      def apply(_conn: ClientConnection): Future[Service[Req, Rep]] = f()
      def close(deadline: Time): Future[Unit] = Future.Done
    }
}
