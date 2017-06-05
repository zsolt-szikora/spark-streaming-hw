package com.epam.streaming

import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.{DStream, InputDStream}

import scala.reflect.io.Directory


object StreamingExample {


  def main(args: Array[String]): Unit = {
    require(args.length == 4, "Provide parameters in this order: actorsDataFolderPath, ratingEventsDataFolderPath, minimumNumberOfVotes, minimumNumberOfMovies")

    val actorsFolder = args(0)
    val ratingFolder = args(1)
    val minimumNumberOfVotes = args(2).toInt
    val minimumNumberOfMovies = args(3).toInt

    val spark = SparkSession.builder
      .master("local[4]")
      .appName("Imdb - Spark Core")
      .getOrCreate()

    val sparkContext = spark.sparkContext
    val sparkStreamingContext = new StreamingContext(sparkContext, Seconds(1))
    sparkStreamingContext.checkpoint(createTempDir)

    val imdbEventGenerator = new ImdbEventGenerator(actorsFolder, ratingFolder)
    val actorsOfMovies: InputDStream[String] = sparkStreamingContext.queueStream(imdbEventGenerator.buildImdbEventStream(sparkContext, DataType.ActorData))
    val movieRatingEvents: InputDStream[String] = sparkStreamingContext.queueStream(imdbEventGenerator.buildImdbEventStream(sparkContext, DataType.RatingData))

    // printing 15-15 lines from each input file in each microbatch
    actorsOfMovies.print(15)
    movieRatingEvents.print(15)


    // =========================================   TASK 1:   =========================================
    // |   printing out the 10 most busy actors (those actors who played in the most of the films)   |
    // ===============================================================================================
    val numberOfMoviesByActors: DStream[(String, Long)] = task1(actorsOfMovies)

    numberOfMoviesByActors.foreachRDD(rdd => {
      println("\n==== TASK 1: busy actors ====")
      rdd
        .takeOrdered(10)(Ordering[Long].reverse.on(_._2)) // taking the actors with the highest 10 movie number
        .foreach(data => println(s"actor: ${data._1}, number of movies:  ${data._2}"))
    })


    // =========================================   TASK 2:   =========================================
    // |   printing out the 10 best actors (those, who get the highest average rates and also voted  |
    // |   at least 'minimumNumberOfVotes' times)                                                    |
    // |   NOTE! each line in the movie rating datafile means 1000 actual votes                      |
    // ===============================================================================================
    val actorRatingsSinceBeginningOfTime: DStream[(String, Long, Float)] =
    task2(actorsOfMovies, movieRatingEvents)

    actorRatingsSinceBeginningOfTime
      .filter(_._2 >= minimumNumberOfVotes)
      .foreachRDD(rdd => {
        println(s"\n==== TASK 2: best actors (at least $minimumNumberOfVotes votes) ====")
        rdd
          .takeOrdered(10)(Ordering[Float].reverse.on(_._3)) // taking the actors with the highest 10 average ratings
          .foreach(data => println(s"actor: ${data._1}, average rate:  ${data._3}, number of votes: ${data._2}"))
      })


    // =========================================   TASK 3:   =========================================
    // |   printing out the 10 best busy actors (those, who get the highest average rates and also   |
    // |   played in at least 'minimumNumberOfMovies' movies). We don't care about the number of     |
    // |   votes in this task)                                                                       |
    // ===============================================================================================
    val actorRatingsAndMovieNumberSinceBiginningOfTime: DStream[(String, Float, Long)] =
    task3(actorRatingsSinceBeginningOfTime, numberOfMoviesByActors, minimumNumberOfMovies)

    actorRatingsAndMovieNumberSinceBiginningOfTime
      .foreachRDD(rdd => {
        println(s"\n==== TASK 3: best busy actors (at least $minimumNumberOfMovies movies) ====")
        rdd
          .takeOrdered(10)(Ordering[Float].reverse.on(_._2)) // taking the actors with the highest 10 average ratings
          .foreach(data => println(s"actor: ${data._1}, average rate:  ${data._2}, number of movies: ${data._3}"))
      })


    sparkStreamingContext.start() // Start the computation
    sparkStreamingContext.awaitTermination() // Wait for the computation to terminate
    spark.stop()
  }


  /**
    * function implementing task 1
    *
    * @param actorsOfMovies each line of the actor data input file fetched in the given microbatch
    * @return a DStream of:
    *         - actor name,
    *         - number of movies where the actor played since the beginning of time
    */
  def task1(actorsOfMovies: InputDStream[String]): DStream[(String, Long)] = {

    val stringArrayStream = actorsOfMovies.map(line => line.split("\t"))
    val pairs = stringArrayStream.map(stringArray => (stringArray(0), 1L))

    def updateFunction(newValues: Seq[Long], runningCount: Option[Long]): Some[Long] = {
      // Note: newValues.sum === newValues.size and the latter is quicker
      val newCount = runningCount.getOrElse(0L) + newValues.size
      Some(newCount)
    }

    pairs.updateStateByKey(updateFunction)
  }


  /**
    * function implementing task 2
    *
    * You need to calculate the average rate by taking all the votes into account (and not by taking the average of the averages)
    *
    * @param actorsOfMovies    each line of the actor data input file fetched in the given microbatch
    * @param movieRatingEvents each line of the movie rating data input file fetched in the given microbatch
    *                          NOTE! each line in the data file means 1000 votes
    * @return a DStream of:
    *         - actor name,
    *         - number of votes since the beginning of time for the movies where the actor playes,
    *         - average rating of movies where the actor played since the beginning of time
    */
  def task2(actorsOfMovies: InputDStream[String], movieRatingEvents: InputDStream[String]): DStream[(String, Long, Float)] = {

    // == DELETE THE NEXT LINE AND ADD YOUR OWN IMPLEMENTATION ==

    val MovieYear2Actor: DStream[(String, String)] = actorsOfMovies
      .map(line => line.split("\t"))
      .map(stringArray => (stringArray(1) + stringArray(2), stringArray(0)))

    val movieYear2rating: DStream[(String, Double)] = movieRatingEvents
      .map(line => line.split("\t"))
      .map(stringArray => (stringArray(1) + stringArray(2), stringArray(0).toDouble))

    val actor2OneAndrating: DStream[(String, (Long, Double))] = MovieYear2Actor
      .join(movieYear2rating)
      .map { case (movieYear, (actor, rating)) => (actor, (1L, rating)) }

    val updateFunction = (newRatingsAndVotes: Seq[(Long, Double)], runningState: Option[(Long, Double)]) => {
      val valuesSofar: (Long, Double) = runningState.getOrElse(0L, 0d)
      val updatedValues = newRatingsAndVotes.fold(valuesSofar) {
        case ((vote, rating), (otherVote, otherRating)) => (vote + otherVote, rating + otherRating)
      }
      Some(updatedValues)
    }

    val updatedState: DStream[(String, (Long, Double))] = actor2OneAndrating.updateStateByKey(updateFunction)

    updatedState.map {
      case (actor, (votes, ratings)) => (actor, 1000L * votes, (ratings / votes).toFloat)
    }
  }

  /**
    * function implementing task 3
    *
    * @param actorRatingsSinceBeginningOfTime result of task2 function
    * @param numberOfMoviesByActors           result of task1 function
    * @param minimumNumberOfMovies            input argument, specifying the minimum (inclusive) number of movies
    * @return a DStream of:
    *         - actor name,
    *         - average rating of movies where the actor played since the beginning of time
    *         - number of movies since the beginning of time where the actor played
    */
  def task3(actorRatingsSinceBeginningOfTime: DStream[(String, Long, Float)],
            numberOfMoviesByActors: DStream[(String, Long)],
            minimumNumberOfMovies: Int): DStream[(String, Float, Long)] = {

    // == DELETE THE NEXT LINE AND ADD YOUR OWN IMPLEMENTATION ==

    val actors2VotesAndRatings = actorRatingsSinceBeginningOfTime.map { case (actor, voteCount, ratingAvg) => (actor, (voteCount, ratingAvg)) }

    numberOfMoviesByActors
      .filter { case (_, movieCount) => movieCount >= minimumNumberOfMovies }
      .join(actors2VotesAndRatings)
      .map { case (actor, (movieCount, (_, ratingAvg))) => (actor, ratingAvg, movieCount) }

  }


  def createTempDir: String = {
    // replacing backslashes to enable it to work on windows
    Directory.makeTemp(suffix = "SparkStreamingHwCheckpoint").toFile.jfile.getAbsolutePath.replace("\\", "/")
  }
}
