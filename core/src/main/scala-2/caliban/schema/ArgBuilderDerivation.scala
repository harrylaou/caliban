package caliban.schema

import caliban.CalibanError.ExecutionError
import caliban.InputValue
import caliban.Value._
import caliban.schema.Annotations.{ GQLDefault, GQLName, GQLOneOfInput }
import magnolia._
import mercator.Monadic

import scala.language.experimental.macros

trait CommonArgBuilderDerivation {

  type Typeclass[T] = ArgBuilder[T]

  type EitherExecutionError[A] = Either[ExecutionError, A]

  implicit val eitherMonadic: Monadic[EitherExecutionError] = new Monadic[EitherExecutionError] {
    override def flatMap[A, B](from: EitherExecutionError[A])(
      fn: A => EitherExecutionError[B]
    ): EitherExecutionError[B] = from.flatMap(fn)

    override def point[A](value: A): EitherExecutionError[A] = Right(value)

    override def map[A, B](from: EitherExecutionError[A])(fn: A => B): EitherExecutionError[B] = from.map(fn)
  }

  def combine[T](ctx: CaseClass[ArgBuilder, T]): ArgBuilder[T] =
    (input: InputValue) =>
      ctx.constructMonadic { p =>
        input match {
          case InputValue.ObjectValue(fields) =>
            val label   = p.annotations.collectFirst { case GQLName(name) => name }.getOrElse(p.label)
            val default = p.annotations.collectFirst { case GQLDefault(v) => v }
            fields.get(label).fold(p.typeclass.buildMissing(default))(p.typeclass.build)
          case value                          => p.typeclass.build(value)
        }
      }

  def dispatch[T](ctx: SealedTrait[ArgBuilder, T]): ArgBuilder[T] =
    if (ctx.annotations.contains(GQLOneOfInput())) makeOneOffBuilder(ctx)
    else makeSumBuilder(ctx)

  private def makeSumBuilder[T](ctx: SealedTrait[ArgBuilder, T]): ArgBuilder[T] = input =>
    (input match {
      case EnumValue(value)   => Some(value)
      case StringValue(value) => Some(value)
      case _                  => None
    }) match {
      case Some(value) =>
        ctx.subtypes
          .find(t =>
            t.annotations.collectFirst { case GQLName(name) => name }.contains(value) || t.typeName.short == value
          ) match {
          case Some(subtype) => subtype.typeclass.build(InputValue.ObjectValue(Map()))
          case None          => Left(ExecutionError(s"Invalid value $value for trait ${ctx.typeName.short}"))
        }
      case None        => Left(ExecutionError(s"Can't build a trait from input $input"))
    }

  private def makeOneOffBuilder[A](ctx: SealedTrait[ArgBuilder, A]): ArgBuilder[A] = new ArgBuilder.OneOff[A] {
    private lazy val builders = ctx.subtypes.map(_.typeclass)

    def build(input: InputValue): Either[ExecutionError, A] = input match {
      case InputValue.ObjectValue(value) if value.size == 1 =>
        builders.view
          .map(_.build(input))
          .find(_.isRight)
          .getOrElse(Left(ExecutionError(s"Invalid oneOff input $value for trait ${ctx.typeName.short}")))
      case InputValue.ObjectValue(_)                        => Left(ExecutionError("Exactly one key must be specified for oneOff inputs"))
      case _                                                => Left(ExecutionError(s"Can't build a trait from input $input"))
    }
  }

}

trait ArgBuilderDerivation extends CommonArgBuilderDerivation {
  def gen[T]: Typeclass[T] = macro Magnolia.gen[T]
}

trait AutoArgBuilderDerivation extends ArgBuilderInstances with LowPriorityDerivedArgBuilder

private[schema] trait LowPriorityDerivedArgBuilder extends CommonArgBuilderDerivation {
  implicit def genAuto[T]: Typeclass[T] = macro Magnolia.gen[T]
}
