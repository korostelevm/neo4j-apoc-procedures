package apoc.algo.pagerank;

import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Result;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicIntegerArray;
import static apoc.algo.pagerank.PageRankUtils.toFloat;
import static apoc.algo.pagerank.PageRankUtils.toInt;

public class PageRankArrayStorageParallelCypher implements PageRank
{
    public static final int ONE_MINUS_ALPHA_INT = toInt( ONE_MINUS_ALPHA );
    public static final int WRITE_BATCH=100_100;
    public static final int INITIAL_ARRAY_SIZE=100_000;
    public final int BATCH_SIZE = 100_000 ;
    private final GraphDatabaseAPI db;
    private final ExecutorService pool;
    private int nodeCount;
    private int relCount;


    /*
        1. Memory usage right now:
            4 arrays : size of nodes.
            2 arrays : size of relationships.
            Memory usage: 4*N + 2*M
     */

    int [] nodeMapping;
    int [] sourceDegreeData;

    // Storing relationships
    int [] relationshipTarget;
    int [] relationshipWeight;

    // Output arrays.
    int [] previousPageRanks;
    private AtomicIntegerArray pageRanksAtomic;

    public PageRankArrayStorageParallelCypher(
            GraphDatabaseAPI db,
            ExecutorService pool)
    {
        this.pool = pool;
        this.db = db;
    }

    private int getNodeIndex(int node) {
        int index = Arrays.binarySearch(nodeMapping, 0, nodeCount, node);
        return index;
    }

    // TODO Create buckets instead of copying data.
    // Not doing it right now because of the complications of the interface.
    private int [] doubleSize(int [] array, int currentSize) {
        int newArray[] = new int[currentSize * 2];
        System.arraycopy(array, 0, newArray, 0, currentSize);
        return newArray;
    }

    public boolean readDataIntoArray(String relCypher, String nodeCypher) {
        Result nodeResult = db.execute(nodeCypher);

        long before = System.currentTimeMillis();
        ResourceIterator<Object> resultIterator = nodeResult.columnAs("id");
        int index = 0;
        int totalNodes = 0;
        nodeMapping = new int[INITIAL_ARRAY_SIZE];
        int currentSize = INITIAL_ARRAY_SIZE;
        while(resultIterator.hasNext()) {
            int node  = ((Long)resultIterator.next()).intValue();

            if (index >= currentSize) {
                System.out.println("Node Doubling size " + currentSize);
                nodeMapping = doubleSize(nodeMapping, currentSize);
                currentSize = currentSize * 2;
            }
            nodeMapping[index] = node;
            index++;
            totalNodes++;
        }

        this.nodeCount = totalNodes;
        Arrays.sort(nodeMapping, 0, nodeCount);
        long after = System.currentTimeMillis();
        System.out.println("Time to make nodes structure = " + (after - before) + " millis");

        sourceDegreeData = new int[totalNodes];
        previousPageRanks = new int[totalNodes];
        pageRanksAtomic = new AtomicIntegerArray(totalNodes);

        before = System.currentTimeMillis();
        Result result = db.execute(relCypher);
        after = System.currentTimeMillis();
        System.out.println("Time to execute relationship cypher = " + (after - before) + " millis");

        int currentRelationSize = INITIAL_ARRAY_SIZE;
        relationshipTarget = new int[currentRelationSize];
        relationshipWeight = new int[currentRelationSize];

        Arrays.fill(relationshipTarget, -1);
        Arrays.fill(relationshipWeight, -1);

        int totalRelationships = 0;
        int previousSource = -1;
        int currentChunkIndex = 0;
        before = System.currentTimeMillis();
        while(result.hasNext()) {
            Map<String, Object> res = result.next();
            int source = ((Long) res.get("source")).intValue();
            if (source < previousSource) {
                System.out.println("Source nodes are not ordered in relationship cypher.");
                return false;
            }
            previousSource = source;
            int target = ((Long) res.get("target")).intValue();
            int weight = ((Long) res.getOrDefault("weight", 1)).intValue();
            int sourceIndex = getNodeIndex(source);

            int storedDegree = sourceDegreeData[sourceIndex];
            if (storedDegree == 0) {
                sourceDegreeData[sourceIndex] = 1;
            } else {
                sourceDegreeData[sourceIndex] = storedDegree + 1;
            }

            // Add the relationships.
            if (totalRelationships >= currentRelationSize) {
                relationshipTarget = doubleSize(relationshipTarget, currentRelationSize);
                relationshipWeight = doubleSize(relationshipWeight, currentRelationSize);
                currentRelationSize = 2 * currentRelationSize;
            }

            int logicalTargetIndex = getNodeIndex(target);
            relationshipTarget[currentChunkIndex] = logicalTargetIndex;
            relationshipWeight[currentChunkIndex] = weight;

            currentChunkIndex++;

            if (totalRelationships % BATCH_SIZE == 0) {
                System.out.println("Processed " + totalRelationships + " relationships");
            }
            totalRelationships++;
        }

        after = System.currentTimeMillis();
        System.out.println("Time for iteration over " + totalRelationships + " relations = " + (after - before) + " millis");
        this.relCount = totalRelationships;
        result.close();
        return true;
    }

    @Override
    public void compute(int iterations, RelationshipType... relationshipTypes) {
        for (int iteration = 0; iteration < iterations; iteration++) {
            long before = System.currentTimeMillis();
            startIteration();
            iterateParallel(iteration);
            long after = System.currentTimeMillis();
            System.out.println("Time for iteration " + iteration + "  " + (after - before) + " millis");
        }
    }

    private int getEndNode(int node) {
        int endNode = node;
        int totalRelationships = 0;
        while(endNode < nodeCount &&
                (totalRelationships <= BATCH_SIZE)) {
            totalRelationships+= sourceDegreeData[endNode];
            endNode++;
        }

        return endNode;
    }
    private void iterateParallel(int iter) {
        int batches = (int)nodeCount/BATCH_SIZE;
        List<Future> futures = new ArrayList<>(batches);
        int nodeIter = 0;
        int batchNo = 0;
        while(nodeIter < nodeCount) {
            // Process BATCH_SIZE relationships in one batch, aligned to the chunksize.
            final int start = nodeIter;
            final int end = getEndNode(nodeIter);
            Future future = pool.submit(new Runnable() {
                @Override
                public void run() {
                    int relProcessed = 0;
                    for (int i = start; i < end; i++) {
                        int chunkIndex = getStartingTargetIndex(i);
                        int degree = sourceDegreeData[i];

                        for (int j = 0; j < degree; j++) {
                            int source = i;
                            int target = relationshipTarget[chunkIndex + j];
                            int weight = relationshipWeight[chunkIndex + j];
                            pageRanksAtomic.addAndGet(target, weight * previousPageRanks[source]);
                            relProcessed++;
                        }
                    }

                    if (iter == 0)
                    System.out.println(Thread.currentThread().getName() + " processed " + relProcessed);
                }
            });
            batchNo++;
            nodeIter = end;
            futures.add(future);
        }

        PageRankUtils.waitForTasks(futures);
    }

    private int getStartingTargetIndex(int node) {
        int startingIndex = 0;
        if (sourceDegreeData[node] == 0) {
            return -1;
        }
        for (int i = 0; i < node; i++) {
            startingIndex += sourceDegreeData[i];
        }
        return startingIndex;
    }

    private int getTotalWeightForNode(int node) {
        int chunkIndex = getStartingTargetIndex(node);
        int degree = sourceDegreeData[node];
        int totalWeight = 0;
        for (int i = 0; i < degree; i++) {
            totalWeight += relationshipWeight[chunkIndex + i];
        }
        return totalWeight;
    }

    private void startIteration()
    {
        for (int node = 0; node < nodeCount; node++) {
            int weightedDegree = getTotalWeightForNode(node);

            if (weightedDegree == -1) {
                continue;
            }
            int prevRank = pageRanksAtomic.get(node);
            previousPageRanks[node] =  toInt(ALPHA * toFloat(prevRank) / weightedDegree);
            pageRanksAtomic.set(node, ONE_MINUS_ALPHA_INT);
        }
    }

    public void writeResultsToDB() {
        PageRankUtils.writeBackResults(pool, db, nodeMapping, this, WRITE_BATCH);
    }

    public double getResult(long node)
    {
        double val = 0;
        int logicalIndex = getNodeIndex((int)node);

        if (logicalIndex >= 0 && pageRanksAtomic.length() >= logicalIndex) {
            val = toFloat(pageRanksAtomic.get(logicalIndex));
        }
        return val;
    }

    public String getPropertyName()
    {
        return "pagerank";
    }

    public long numberOfNodes() {
        return nodeCount;
    };

    public long numberOfRels(){
        return relCount;
    };
}
