package edu.jhu.thrax.hadoop.features.pivot;

import java.util.HashSet;
import java.util.Set;

import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Text;

public class PivotedLhsGivenTargetPhraseFeature extends NonAggregatingPivotedFeature {

  private static final Text LABEL = new Text("p(LHS|e)");

  public String getName() {
    return "lhs_given_e";
  }

  public Text getFeatureLabel() {
    return LABEL;
  }

  public Set<String> getPrerequisites() {
    Set<String> prereqs = new HashSet<String>();
    prereqs.add("lhs_given_e_inv");
    return prereqs;
  }

  public DoubleWritable pivot(MapWritable src, MapWritable tgt) {
    return new DoubleWritable(((DoubleWritable) tgt.get(new Text("p(LHS|e_inv)"))).get());
  }

  @Override
  public Set<Text> getLowerBoundLabels() {
    Set<Text> lower_bound_labels = new HashSet<Text>();
    lower_bound_labels.add(new Text("p(LHS|e_inv)"));
    return lower_bound_labels;
  }

  @Override
  public Set<Text> getUpperBoundLabels() {
    return null;
  }
}
