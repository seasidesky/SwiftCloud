/*****************************************************************************
 * Copyright 2011-2012 INRIA
 * Copyright 2011-2012 Universidade Nova de Lisboa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *****************************************************************************/
package sys.stats.overtime;

import java.util.List;

import swift.utils.Pair;
import sys.stats.StatsConstants;
import sys.stats.common.PlotValues;
import sys.stats.output.HistogramOutput;
import sys.stats.sliced.slices.histogram.CommDistributionImpl;
import sys.stats.sliced.slices.histogram.Histogram;
import sys.stats.sources.ValueSignalSource;

public class HistogramOverTime extends GenericStatisticsOverTime<CommDistributionImpl> implements ValueSignalSource,
        HistogramOutput {

    private double[] commValues;
    private double max;

    public HistogramOverTime(long timeSlice, double[] commValues, String sourceName) {
        super(timeSlice, new CommDistributionImpl(sourceName, commValues));
        this.commValues = commValues;
        // TODO: Fix in case commValues = { Double.MAX_VALUE} is passed
        this.max = commValues[commValues.length - 1];
    }

    @Override
    public void setValue(double value) {
        if (value > max)
            max = value;
        CommDistributionImpl slice = getCurrentSlice();
        slice.addValue(value);
    }

    @Override
    public Stopper createEventDurationSignal() {
        final long TS = System.currentTimeMillis();
        Stopper stopper = new Stopper() {
            public void stop() {
                long TE = System.currentTimeMillis();
                long duration = TE - TS;
                setValue(duration);
            }

        };
        return stopper;

    }

    @Override
    public PlotValues<Long, Histogram> getPlotValues() {
        List<Pair<Long, CommDistributionImpl>> slices = getAllSlices();
        PlotValues<Long, Histogram> histogram = new PlotValues<Long, Histogram>();
        for (Pair<Long, CommDistributionImpl> s : slices) {
            histogram.addValue(s.getFirst(), s.getSecond());
        }
        return histogram;
    }

    @Override
    public String getHistogramBinValues() {
        char ds = StatsConstants.VS;
        String header = "TIME" + ds + "OCURRENCES_VALUE_-INF_TO_" + (int) (commValues.length == 1 ? max : commValues[0]);
        for (int i = 1; i < commValues.length; i++) {
            header += ds + "VALUE_" + (int) commValues[i - 1] + "_TO_"
                    + (int) (i == commValues.length - 1 ? max : commValues[i]);
        }
        return header;
    }
}
