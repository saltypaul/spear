package scraper

import scraper.expressions.UnresolvedAttribute
import scraper.plans.logical.{Limit, LogicalPlan, UnresolvedRelation}
import scraper.trees.{Rule, RulesExecutor}

class Analyzer(catalog: Catalog) extends RulesExecutor[LogicalPlan] {
  override def batches: Seq[RuleBatch] = Seq(
    RuleBatch("Resolution", FixedPoint.Unlimited, Seq(
      ResolveRelations,
      ResolveReferences
    )),

    RuleBatch("Type checking", FixedPoint.Unlimited, Seq(
      ApplyImplicitCasts
    ))
  )

  override def apply(tree: LogicalPlan): LogicalPlan = {
    logTrace(
      s"""Analyzing logical query plan:
         |
         |${tree.prettyTree}
         |""".stripMargin
    )
    super.apply(tree)
  }

  object ResolveReferences extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformUp {
      case plan if !plan.resolved =>
        plan transformExpressionsUp {
          case UnresolvedAttribute(name) =>
            def reportResolutionFailure(message: String): Nothing = {
              throw ResolutionFailureException(
                s"""Failed to resolve attribute $name in logical query plan:
                   |
                   |${plan.prettyTree}
                   |
                   |$message
                   |""".stripMargin
              )
            }

            val candidates = plan.children flatMap (_.output) filter (_.name == name)

            candidates match {
              case Seq(attribute) =>
                attribute

              case Nil =>
                reportResolutionFailure("No candidate input attribute(s) found")

              case _ =>
                reportResolutionFailure({
                  val list = candidates.map(_.nodeCaption).mkString(", ")
                  s"Multiple ambiguous input attributes found: $list"
                })
            }
        }
    }
  }

  object ResolveRelations extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformUp {
      case UnresolvedRelation(name) => catalog lookupRelation name
    }
  }

  object FoldableLimit extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case plan @ (_ Limit limit) if limit.foldable => plan
      case plan: Limit =>
        throw new RuntimeException("Limit must be a constant")
    }
  }

  object ApplyImplicitCasts extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case plan => plan.strictlyTyped.get
    }
  }
}
