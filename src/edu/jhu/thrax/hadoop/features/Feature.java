package edu.jhu.thrax.hadoop.features;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.lib.reduce.IntSumReducer;

import edu.jhu.thrax.hadoop.datatypes.RuleWritable;

public abstract class Feature extends Mapper<RuleWritable, IntWritable,
                                    RuleWritable, IntWritable>
                                        implements Comparable<Feature>
{
    public Class<? extends Reducer> combinerClass()
    {
        return IntSumReducer.class;
    }

    public abstract Class<? extends WritableComparator> sortComparatorClass();

    public abstract Class<? extends Partitioner<RuleWritable, IntWritable>> partitionerClass();

    public int compareTo(Feature other)
    {
        int cmp = sortComparatorClass().getName().compareTo(other.sortComparatorClass().getName());
        if (cmp != 0)
            return cmp;
        return partitionerClass().getName().compareTo(other.partitionerClass().getName());
    }

    public boolean canReduceWith(Feature other)
    {
        return sortComparatorClass().equals(other.sortComparatorClass()) &&
               partitionerClass().equals(other.partitionerClass());
    }
}
