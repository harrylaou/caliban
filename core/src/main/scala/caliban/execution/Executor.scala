package caliban.execution

import scala.collection.immutable.ListMap
import caliban.CalibanError.ExecutionError
import caliban.parsing.adt.ExecutableDefinition.{ FragmentDefinition, OperationDefinition }
import caliban.parsing.adt.OperationType.{ Mutation, Query, Subscription }
import caliban.parsing.adt.Selection.{ Field, FragmentSpread, InlineFragment }
import caliban.parsing.adt.{ Directive, Document, Selection, Value, VariableDefinition }
import caliban.ResolvedValue.{ ResolvedListValue, ResolvedObjectValue, ResolvedStreamValue }
import caliban.{ ResolvedValue, ResponseValue }
import caliban.ResponseValue.{ ListValue, NullValue, ObjectValue, StringValue }
import caliban.schema.RootSchema.Operation
import caliban.schema.RootSchema
import zio.{ IO, UIO }

object Executor {

  /**
   * Executes the given query against a schema. It returns either an [[caliban.CalibanError.ExecutionError]] or a [[ResponseValue]].
   * @param document the parsed query
   * @param schema the schema to use to run the query
   * @param operationName the operation to run in case the query contains multiple operations.
   * @param variables a list of variables.
   */
  def executeRequest[Q, M, S](
    document: Document,
    schema: RootSchema[Q, M, S],
    operationName: Option[String] = None,
    variables: Map[String, Value] = Map()
  ): IO[ExecutionError, ResponseValue] = {
    val fragments = document.definitions.collect {
      case fragment: FragmentDefinition => fragment.name -> fragment
    }.toMap
    val operation = operationName match {
      case Some(name) =>
        document.definitions.collectFirst { case op: OperationDefinition if op.name.contains(name) => op }
          .toRight(s"Unknown operation $name.")
      case None =>
        document.definitions.collect { case op: OperationDefinition => op } match {
          case head :: Nil => Right(head)
          case _           => Left("Operation name is required.")
        }
    }
    IO.fromEither(operation).mapError(ExecutionError(_)).flatMap { op =>
      def executeOperation[A](x: Operation[A], parallel: Boolean): IO[ExecutionError, ResponseValue] =
        executeSelectionSet(
          x.schema.resolve(x.resolver, Map()),
          op.selectionSet,
          fragments,
          op.variableDefinitions,
          variables,
          parallel
        )
      op.operationType match {
        case Query => executeOperation(schema.query, parallel = true)
        case Mutation =>
          schema.mutation match {
            case Some(m) => executeOperation(m, parallel = false)
            case None    => IO.fail(ExecutionError("Mutations are not supported on this schema"))
          }
        case Subscription =>
          schema.subscription match {
            case Some(m) => executeOperation(m, parallel = true)
            case None    => IO.fail(ExecutionError("Subscriptions are not supported on this schema"))
          }
      }
    }
  }

  private def executeSelectionSet(
    resolve: IO[ExecutionError, ResolvedValue],
    selectionSet: List[Selection],
    fragments: Map[String, FragmentDefinition],
    variableDefinitions: List[VariableDefinition],
    variableValues: Map[String, Value],
    parallel: Boolean
  ): IO[ExecutionError, ResponseValue] = {

    def executeSelectionSetLoop(
      resolve: IO[ExecutionError, ResolvedValue],
      selectionSet: List[Selection]
    ): IO[ExecutionError, ResponseValue] =
      resolve.flatMap {
        case ResolvedObjectValue(objectName, fields) =>
          val mergedSelectionSet = mergeSelectionSet(selectionSet, objectName, fragments, variableValues)
          val resolveFields = mergedSelectionSet.map {
            case Selection.Field(alias, name @ "__typename", _, _, _) =>
              UIO(alias.getOrElse(name) -> StringValue(objectName))
            case Selection.Field(alias, name, args, _, selectionSet) =>
              val arguments = resolveVariables(args, variableDefinitions, variableValues)
              fields
                .get(name)
                .map(res => executeSelectionSetLoop(res(arguments), selectionSet))
                .getOrElse(UIO.succeed(NullValue))
                .map((alias.getOrElse(name), _))
          }
          (if (parallel) IO.collectAllPar(resolveFields) else IO.collectAll(resolveFields)).map(ObjectValue)
        case ResolvedListValue(values) =>
          IO.collectAllPar(values.map(executeSelectionSetLoop(_, selectionSet))).map(ListValue)
        case ResolvedStreamValue(stream) =>
          UIO(ResponseValue.StreamValue(stream.mapM(res => executeSelectionSetLoop(UIO(res), selectionSet))))
        case other: ResponseValue => UIO(other)
      }

    executeSelectionSetLoop(resolve, selectionSet)
  }

  private def resolveVariables(
    arguments: Map[String, Value],
    variableDefinitions: List[VariableDefinition],
    variableValues: Map[String, Value]
  ): Map[String, Value] =
    arguments.map {
      case (k, v) =>
        k -> (v match {
          case Value.VariableValue(name) =>
            variableValues.get(name) orElse variableDefinitions.find(_.name == name).flatMap(_.defaultValue) getOrElse v
          case value => value
        })
    }

  private def mergeSelectionSet(
    selectionSet: List[Selection],
    name: String,
    fragments: Map[String, FragmentDefinition],
    variableValues: Map[String, Value]
  ): List[Field] = {
    val fields = selectionSet.flatMap {
      case f: Field if checkDirectives(f.directives, variableValues) => List(f)
      case InlineFragment(typeCondition, directives, sel) if checkDirectives(directives, variableValues) =>
        val matching = typeCondition.fold(true)(_.name == name)
        if (matching) mergeSelectionSet(sel, name, fragments, variableValues) else Nil
      case FragmentSpread(spreadName, directives) if checkDirectives(directives, variableValues) =>
        fragments.get(spreadName) match {
          case Some(fragment) if fragment.typeCondition.name == name =>
            mergeSelectionSet(fragment.selectionSet, name, fragments, variableValues)
          case _ => Nil
        }
      case _ => Nil
    }
    fields
      .foldLeft(ListMap.empty[String, Field]) {
        case (result, field) =>
          result.updated(
            field.name,
            result
              .get(field.name)
              .fold(field)(f => f.copy(selectionSet = f.selectionSet ++ field.selectionSet))
          )
      }
      .values
      .toList
  }

  private def checkDirectives(directives: List[Directive], variableValues: Map[String, Value]): Boolean =
    !checkDirective("skip", default = false, directives, variableValues) &&
      checkDirective("include", default = true, directives, variableValues)

  private def checkDirective(
    name: String,
    default: Boolean,
    directives: List[Directive],
    variableValues: Map[String, Value]
  ): Boolean =
    directives
      .find(_.name == name)
      .flatMap(_.arguments.get("if")) match {
      case Some(Value.BooleanValue(value)) => value
      case Some(Value.VariableValue(name)) =>
        variableValues
          .get(name) match {
          case Some(Value.BooleanValue(value)) => value
          case _                               => default
        }
      case _ => default
    }

}
