package au.csiro.data61.randomwalk.algorithm

import java.util

import au.csiro.data61.randomwalk.common.{FileManager, Params}
import org.apache.log4j.LogManager

import scala.util.Random
import scala.util.control.Breaks.{break, breakable}

case class UniformRandomWalk(config: Params) extends Serializable {

  def computeAffecteds(vertices: Array[Int], affectedLength: Int): Array[(Int,
    Array[Int])] = {

    vertices.map { v =>
      def computeAffecteds(afs: Array[Int], visited: util.HashSet[Int], v: Int, al: Int,
                           length: Int): Unit = {
        if (length >= al)
          return
        visited.add(v)
        val neighbors = GraphMap.getNeighbors(v)
        if (neighbors != null) {
          for (n <- neighbors) {
            if (!visited.contains(n._1)) {
              afs(length) += 1
              visited.add(n._1)
              computeAffecteds(afs, visited, n._1, al, length + 1)
            }
          }
        }
      }

      val affecteds = new Array[Int](affectedLength)
      val visited = new util.HashSet[Int]()
      visited.add(v)
      affecteds(0) = 1
      computeAffecteds(affecteds, visited, v, affectedLength, 0)

      (v, affecteds)
    }.sortBy(_._2.last)
  }

  def degrees(): Array[(Int, Int)] = {
    val vertices = GraphMap.getVertices()
    val n = vertices.length
    val degs = new Array[(Int, Int)](n)
    for (i <- 0 until n) {
      degs(i) = (vertices(i), GraphMap.getNeighbors(vertices(i)).length)
    }
    degs
  }


  def computeProbs(paths: Array[Array[Int]]): Array[Array[Double]] = {
    val n = GraphMap.getVertices().length
    val matrix = Array.ofDim[Double](n, n)
    paths.foreach { case p =>
      for (i <- 0 until p.length - 1) {
        matrix(p(i) - 1)(p(i + 1) - 1) += 1
      }
    }

    matrix.map { row =>
      val sum = row.sum
      row.map { o =>
        o / sum.toDouble
      }
    }
  }


  lazy val logger = LogManager.getLogger("rwLogger")
  var nVertices: Int = 0
  var nEdges: Int = 0

  def execute(): Array[Array[Int]] = {
    firstOrderWalk(loadGraph())
  }

  /**
    * Loads the graph and computes the probabilities to go from each vertex to its neighbors
    *
    * @return
    */
  def loadGraph(): Array[(Int, Array[Int])] = {

    val g: Array[(Int, Array[(Int, Float)])] = FileManager(config).readFromFile()
    initRandomWalk(g)
  }

  def checkGraphMap() = {
    //    save(degrees())
    println(degrees().sortBy(_._1).map { case (v, d) => s"$v\t$d" }.mkString("\n"))
    for (v <- GraphMap.getVertices().sortBy(a => a)) {
      val n = GraphMap.getNeighbors(v).map(_._1)
      println(s"$v -> ${n.mkString(" ")}")
    }
  }

  def initWalker(v: Int): Array[(Int, Array[Int])] = {
    Array.fill(config.numWalks)(Array((v, Array(v)))).flatMap(a => a)
  }


  def initRandomWalk(g: Array[(Int, Array[(Int, Float)])]): Array[(Int, Array[Int])] = {
    buildGraphMap(g)

    nVertices = g.length
    nEdges = 0
    g.foreach(nEdges += _._2.length)

//    logger.info(s"edges: $nEdges")
//    logger.info(s"vertices: $nVertices")
    println(s"edges: $nEdges")
    println(s"vertices: $nVertices")

    createWalkers(g)
  }

  def createWalkers(g: Array[(Int, Array[(Int, Float)])]): Array[(Int, Array[Int])] = {
    g.flatMap {
      case (vId: Int, _) =>
        Array.fill(config.numWalks)((vId, Array(vId)))
    }
  }

  def firstOrderWalk(initPaths: Array[(Int, Array[Int])], nextFloat: () => Float = Random
    .nextFloat): Array[Array[Int]] = {
    val walkLength = config.walkLength

    val paths: Array[Array[Int]] = initPaths.map { case (_, steps) =>
      var path = steps
      val rSample = RandomSample(nextFloat)
      breakable {
        while (path.length < walkLength + 1) {
          val neighbors = GraphMap.getNeighbors(path.last)
          if (neighbors != null && neighbors.length > 0) {
            val (nextStep, _) = rSample.sample(neighbors)
            path = path ++ Array(nextStep)
          } else {
            break
          }
        }
      }
      path
    }

    paths
  }

  def buildGraphMap(graph: Array[(Int, Array[(Int, Float)])]): Unit = {
    GraphMap.reset // This is only to run on a single executor.
    graph.foreach { case (vId, neighbors) =>
      GraphMap.addVertex(vId, neighbors)
    }

  }

  def queryPaths(paths: Array[Array[Int]]): Array[(Int, (Int, Int))] = {
    var nodes: Array[Int] = Array.empty[Int]
    var numOccurrences: Array[(Int, (Int, Int))] = null
    if (config.nodes.isEmpty) {
      numOccurrences = paths.flatMap { case steps =>
        steps.groupBy(a => a).map { case (a, occurs) => (a, (occurs.length, 1)) }
      }.groupBy(_._1).map { case (a, summary) =>
        var occurs = 0
        var appeared = 0
        summary.foreach { case (_, (occ, app)) =>
          occurs += occ
          appeared += app
        }
        (a, (occurs, appeared))
      }.toArray

    } else {
      nodes = config.nodes.split("\\s+").map(s => s.toInt)
      numOccurrences = new Array[(Int, (Int, Int))](nodes.length)

      for (i <- 0 until nodes.length) {
        numOccurrences(i) = (nodes(i),
          paths.map { case steps =>
            val counts = steps.count(s => s == nodes(i))
            val occurs = if (counts > 0) 1 else 0
            (counts, occurs)
          }.reduce((c, o) => (c._1 + o._1, c._2 + o._2)))
      }
    }

    numOccurrences
  }

}
