package de.lolhens.cats.effect.utils

import cats.effect.kernel.syntax.all._
import cats.effect.kernel._
import cats.syntax.all._
import cats.{Eq, Monad}

import scala.concurrent.duration.Duration

object CatsEffectUtils {
  implicit class CatsEffectUtilsOps[F[_], A](val self: F[A]) extends AnyVal {
    def memoizeWithRef(ref: Ref[F, Option[Deferred[F, Option[Either[Throwable, A]]]]])
                      (implicit F: Concurrent[F]): F[A] = {
      def eval: F[A] =
        F.deferred[Option[Either[Throwable, A]]].flatMap { deferred =>
          F.uncancelable { poll =>
            ref.getAndUpdate(_.orElse(deferred.some)).flatMap {
              case None =>
                poll(self).guaranteeCase {
                  case Outcome.Succeeded(fa) => fa.flatMap(e => deferred.complete(e.asRight.some).void)
                  case Outcome.Errored(e) => deferred.complete(e.asLeft.some).void
                  case _ =>
                    ref.set(none) *>
                      deferred.complete(none).void
                }

              case _ => get
            }
          }
        }

      def get: F[A] =
        ref.get.flatMap {
          case Some(deferred) => deferred.get.flatMap {
            case Some(result) => F.fromEither(result)
            case None => get
          }
          case None => eval
        }

      get
    }

    @inline
    def memoizeUnsafe(implicit F: Concurrent[F], SyncF: Sync[F]): F[A] =
      memoizeWithRef(Ref.unsafe(none))

    def memoizeOnSuccessWithRef(ref: Ref[F, Option[Deferred[F, Option[A]]]])
                               (implicit F: Concurrent[F]): F[A] = {
      def eval: F[A] =
        F.deferred[Option[A]].flatMap { deferred =>
          F.uncancelable { poll =>
            ref.getAndUpdate(_.orElse(deferred.some)).flatMap {
              case None =>
                poll(self).guaranteeCase {
                  case Outcome.Succeeded(fa) => fa.flatMap(e => deferred.complete(e.some).void)
                  case _ =>
                    ref.set(none) *>
                      deferred.complete(none).void
                }

              case _ => get
            }
          }
        }

      def get: F[A] =
        ref.get.flatMap {
          case Some(deferred) => deferred.get.flatMap {
            case Some(a) => F.pure(a)
            case None => get
          }
          case None => eval
        }

      get
    }

    @inline
    def memoizeOnSuccess(implicit F: Concurrent[F]): F[F[A]] =
      F.ref[Option[Deferred[F, Option[A]]]](none).map(memoizeOnSuccessWithRef(_))

    @inline
    def memoizeOnSuccessUnsafe(implicit F: Concurrent[F], SyncF: Sync[F]): F[A] =
      memoizeOnSuccessWithRef(Ref.unsafe(none))

    // TODO: handle cancellation
    def flatMapOnChangeSyncWithRef[B](ref: Ref[F, Option[(A, Deferred[F, Option[B]])]])
                                     (f: A => F[B])
                                     (implicit A: Eq[A], F: Async[F]): F[Either[Fiber[F, Throwable, B], B]] =
      self.flatMap { a =>
        lazy val lzy@Some((_, deferred)) = Some((a, Deferred.unsafe[F, Option[B]]))

        def complete(f: F[B]): F[B] = f
          .flatTap(e => deferred.complete(e.some))
          .handleErrorWith { throwable =>
            ref.set(none) *>
              deferred.complete(none) *>
              throwable.raiseError
          }

        def run[G[_] : Monad](continue: F[Option[B]] => F[G[B]]): F[G[B]] = {
          ref.getAndUpdate(_.orElse(lzy)).flatMap {
            case Some((prevA, deferred)) if prevA === a =>
              deferred.tryGet.flatMap {
                case Some(Some(sync)) => sync.pure[G].pure[F]
                case _ => continue(deferred.get)
              }
            case Some(_) => ref.set(lzy) *> continue(complete(f(a)).map(_.some))
            case None => continue(complete(f(a)).map(_.some))
          }
        }

        run[({type L[T] = Either[Fiber[F, Throwable, B], T]})#L](deferredGet =>
          deferredGet.flatMap {
            case Some(result) => result.pure
            case None =>
              val runRec = run[({type L[T] = Either[Unit, T]})#L](_.map(_.toRight(())))
              ().tailRecM[F, B](_ => runRec)
          }.start.map(_.asLeft)
        )
      }

    @inline
    def flatMapOnChangeSync[B](f: A => F[B])
                              (implicit A: Eq[A], F: Async[F]): F[F[Either[Fiber[F, Throwable, B], B]]] =
      F.ref[Option[(A, Deferred[F, Option[B]])]](none).map(flatMapOnChangeSyncWithRef[B](_)(f))

    @inline
    def flatMapOnChangeSyncUnsafe[B](f: A => F[B])
                                    (implicit A: Eq[A], F: Async[F]): F[Either[Fiber[F, Throwable, B], B]] =
      flatMapOnChangeSyncWithRef[B](Ref.unsafe(none))(f)

    def allowOldWithRef[B](ref: Ref[F, Option[B]])
                          (fresh: B => Boolean = (_: Any) => true)
                          (implicit ev: A =:= Either[Fiber[F, Throwable, B], B], F: Async[F]): F[B] = {
      def complete(f: F[B]): F[B] = f.flatMap(result =>
        ref.set(result.some).as(result)
      )

      /*ev.liftCo[F](self)*/ self.asInstanceOf[F[Either[Fiber[F, Throwable, B], B]]].flatMap {
        case Right(sync) => complete(sync.pure)
        case Left(fiber) =>
          val async = complete(fiber.joinWithNever)
          ref.get.flatMap {
            case Some(sync) if fresh(sync) => async.start *> sync.pure
            case _ => async
          }
      }
    }

    @inline
    def allowOld[B](fresh: B => Boolean = (_: Any) => true)
                   (implicit ev: A =:= Either[Fiber[F, Throwable, B], B], F: Async[F]): F[F[B]] =
      F.ref[Option[B]](none).map(allowOldWithRef[B](_)(fresh))

    @inline
    def allowOldUnsafe[B](fresh: B => Boolean = (_: Any) => true)
                         (implicit ev: A =:= Either[Fiber[F, Throwable, B], B], F: Async[F]): F[B] =
      allowOldWithRef[B](Ref.unsafe(none))(fresh)

    // TODO: handle cancellation
    def flatMapOnChangeWithRef[B](ref: Ref[F, Option[(A, Deferred[F, Option[B]])]])
                                 (f: A => F[B])
                                 (implicit A: Eq[A], F: Async[F]): F[B] =
      self.flatMap { a =>
        lazy val lzy@Some((_, deferred)) = Some((a, Deferred.unsafe[F, Option[B]]))

        def complete(f: F[B]): F[B] = f
          .flatTap(e => deferred.complete(e.some))
          .handleErrorWith { throwable =>
            ref.set(none) *>
              deferred.complete(none) *>
              throwable.raiseError
          }

        ().tailRecM[F, B] { _ =>
          ref.getAndUpdate(_.orElse(lzy)).flatMap {
            case Some((prevA, deferred)) if prevA === a => deferred.get.map(_.toRight(()))
            case Some(_) => ref.set(lzy) *> complete(f(a)).map(_.asRight)
            case None => complete(f(a)).map(_.asRight)
          }
        }
      }

    @inline
    def flatMapOnChange[B](f: A => F[B])
                          (implicit A: Eq[A], F: Async[F]): F[F[B]] =
      F.ref[Option[(A, Deferred[F, Option[B]])]](none).map(flatMapOnChangeWithRef[B](_)(f))

    @inline
    def flatMapOnChange_[B](f: => F[B])
                           (implicit A: Eq[A], F: Async[F]): F[F[B]] =
      flatMapOnChange[B](_ => f)

    @inline
    def mapOnChange[B](f: A => B)
                      (implicit A: Eq[A], F: Async[F]): F[F[B]] =
      flatMapOnChange[B](a => F.delay(f(a)))

    @inline
    def mapOnChange_[B](f: => B)
                       (implicit A: Eq[A], F: Async[F]): F[F[B]] =
      flatMapOnChange[B](_ => F.delay(f))

    @inline
    def flatMapOnChangeUnsafe[B](f: A => F[B])
                                (implicit A: Eq[A], F: Async[F]): F[B] =
      flatMapOnChangeWithRef[B](Ref.unsafe(none))(f)

    @inline
    def flatMapOnChangeUnsafe_[B](f: => F[B])
                                 (implicit A: Eq[A], F: Async[F]): F[B] =
      flatMapOnChangeUnsafe[B](_ => f)

    @inline
    def mapOnChangeUnsafe[B](f: A => B)
                            (implicit A: Eq[A], F: Async[F]): F[B] =
      flatMapOnChangeUnsafe[B](a => F.delay(f(a)))

    @inline
    def mapOnChangeUnsafe_[B](f: => B)
                             (implicit A: Eq[A], F: Async[F]): F[B] =
      flatMapOnChangeUnsafe[B](_ => F.delay(f))

    // TODO: handle cancellation
    def cacheForWithRef(ref: Ref[F, Option[(Long, Deferred[F, Option[A]])]])
                       (duration: Duration)
                       (implicit F: Async[F]): F[A] =
      F.realTime.flatMap { time =>
        lazy val lzy@Some((_, deferred)) = Some((time.toMillis + duration.toMillis, Deferred.unsafe[F, Option[A]]))

        def complete(f: F[A]): F[A] = f
          .flatTap(e => deferred.complete(e.some))
          .handleErrorWith { throwable =>
            ref.set(none) *>
              deferred.complete(none) *>
              throwable.raiseError
          }

        ().tailRecM[F, A] { _ =>
          ref.getAndUpdate(_.orElse(lzy)).flatMap {
            case Some((expiration, deferred)) if expiration > time.toMillis => deferred.get.map(_.toRight(()))
            case Some(_) => ref.set(lzy) *> complete(self).map(_.asRight)
            case None => complete(self).map(_.asRight)
          }
        }
      }

    @inline
    def cacheFor(duration: Duration)
                (implicit F: Async[F]): F[F[A]] =
      F.ref[Option[(Long, Deferred[F, Option[A]])]](none).map(cacheForWithRef(_)(duration))

    @inline
    def cacheForUnsafe(duration: Duration)
                      (implicit F: Async[F]): F[A] =
      cacheForWithRef(Ref.unsafe(none))(duration)
  }

}
