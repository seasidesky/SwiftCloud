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
package sys.stats;

import static org.junit.Assert.assertEquals;

import java.util.Iterator;
import java.util.Map;

import org.junit.Test;

import sys.stats.common.PlaneValue;
import sys.stats.common.PlotValues;
import sys.stats.sources.PollingBasedValueProvider;

// TODO: add file cleanup 
public class PollingValueTester1 {

    @Test
    public void testpolling() {
        StatsImpl stats = StatsImpl.getInstance("teste");

        UpdatingFieldClass classWithField = new UpdatingFieldClass(1000);

        stats.registerPollingBasedValueProvider("poll", classWithField.getPoller(), 1000);

        try {
            Thread.sleep(5500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        Map<String, PlotValues<Long, Double>> pollingSummary = stats.getPollingSummary();

        System.out.println(pollingSummary);

        PlotValues<Long, Double> pollingValues = pollingSummary.get("poll");
        int increment = 1;
        int expected = 0;
        Iterator<PlaneValue<Long, Double>> it = pollingValues.getPlotValuesIterator();
        while (it.hasNext()) {
            PlaneValue<Long, Double> v = it.next();
            assertEquals(expected, v.getY().intValue());
            expected += increment;
        }

    }

    class UpdatingFieldClass {
        private int theField;

        public UpdatingFieldClass(final int frequency) {
            Thread t = new Thread(new Runnable() {

                @Override
                public void run() {
                    while (true) {
                        theField++;
                        System.out.println(theField);
                        try {
                            Thread.sleep(frequency);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        public PollingBasedValueProvider getPoller() {
            return new PollingBasedValueProvider() {

                @Override
                public double poll() {
                    return theField;
                }
            };
        }
    }

}