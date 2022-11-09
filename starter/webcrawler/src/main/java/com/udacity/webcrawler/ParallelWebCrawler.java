package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final Duration timeout;
  private final int popularWordCount;
  private final int maxDepth;
  private final List<Pattern> ignoredUrls;

  private final PageParserFactory parserFactory;
  private final ForkJoinPool pool;

  @Inject
  ParallelWebCrawler(
          Clock clock,
          PageParserFactory parserFactory,
          @Timeout Duration timeout,
          @PopularWordCount int popularWordCount,
          @MaxDepth int maxDepth,
          @IgnoredUrls List<Pattern> ignoredUrls,
          @TargetParallelism int threadCount) {
    this.clock = clock;
    this.parserFactory = parserFactory;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.maxDepth = maxDepth;
    this.ignoredUrls = ignoredUrls;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {
    Instant deadline = clock.instant().plus(timeout);
    ConcurrentMap<String, Integer> counts = new ConcurrentHashMap<>();
    ConcurrentSkipListSet<String> visitedUrls = new ConcurrentSkipListSet<>();

    for (String url : startingUrls) {
      pool.invoke(new CrawlInternalAction(url, deadline, maxDepth, counts, visitedUrls));
    }

    if (counts.isEmpty()) {
      return new CrawlResult.Builder()
              .setWordCounts(counts)
              .setUrlsVisited(visitedUrls.size())
              .build();
    }
    return new CrawlResult.Builder()
            .setWordCounts(WordCounts.sort(counts, popularWordCount))
            .setUrlsVisited(visitedUrls.size())
            .build();


  }


  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }

  public class CrawlInternalAction extends RecursiveAction {
    private String url;
    private Instant deadline;
    private int maxDepth;
    private ConcurrentMap<String, Integer> counts;
    private ConcurrentSkipListSet<String> visitedUrls;

    private ReentrantLock lock = new ReentrantLock();

    public CrawlInternalAction(String url, Instant deadline, int maxDepth, ConcurrentMap<String, Integer> counts, ConcurrentSkipListSet<String> visitedUrls) {

      this.url = url;
      this.deadline = deadline;
      this.maxDepth = maxDepth;
      this.counts = counts;
      this.visitedUrls = visitedUrls;

    }

    @Override
    protected void compute() {

      if (maxDepth == 0 || clock.instant().isAfter(deadline)) {
        return;
      }

      for (Pattern pattern : ignoredUrls) {
        if (pattern.matcher(url).matches()) {
          return;
        }
      }
      try {
        lock.lock();
        if (visitedUrls.contains(url)) {
          return;
        }
        visitedUrls.add(url);
      } catch (Exception e) {
        e.printStackTrace();
      } finally {
        lock.unlock();
      }

      PageParser.Result pageParserResult = parserFactory.get(url).parse();

      for (ConcurrentMap.Entry<String, Integer> e : pageParserResult.getWordCounts().entrySet()) {
        counts.compute(e.getKey(), (k, v) -> (v == null) ? e.getValue() : e.getValue() + v);

      }

      List<CrawlInternalAction> subtasks = pageParserResult.getLinks().stream()
              .map(r -> new CrawlInternalAction(r, deadline, maxDepth - 1, counts, visitedUrls)).collect(Collectors.toList());
      invokeAll(subtasks);



    }

  }
}