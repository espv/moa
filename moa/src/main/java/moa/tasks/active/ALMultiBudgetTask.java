/*
 *    ALMultiBudgetTask.java
 *    Copyright (C) 2017 Otto-von-Guericke-University, Magdeburg, Germany
 *    @author Cornelius Styp von Rekowski (cornelius.styp@ovgu.de)
 *
 *    This program is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program. If not, see <http://www.gnu.org/licenses/>.
 *    
 */
package moa.tasks.active;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import com.github.javacliparser.FloatOption;
import com.github.javacliparser.IntOption;
import com.github.javacliparser.ListOption;
import com.github.javacliparser.Option;
import com.github.javacliparser.Options;

import moa.classifiers.active.ALClassifier;
import moa.core.ObjectRepository;
import moa.evaluation.ALClassificationPerformanceEvaluator;
import moa.evaluation.LearningCurve;
import moa.evaluation.PreviewCollection;
import moa.evaluation.PreviewCollectionLearningCurveWrapper;
import moa.options.ClassOption;
import moa.options.ClassOptionWithListenerOption;
import moa.options.EditableMultiChoiceOption;
import moa.streams.ExampleStream;
import moa.tasks.TaskMonitor;

/**
 * This task individually evaluates an active learning classifier for each 
 * element of a set of budgets. The individual evaluation is done by 
 * prequential evaluation (testing, then training with each example in 
 * sequence).
 * 
 * @author Cornelius Styp von Rekowski (cornelius.styp@ovgu.de)
 * @version $Revision: 1 $
 */
public class ALMultiBudgetTask extends ALMainTask {
	
	private static final long serialVersionUID = 1L;
	
	/* options actually used in ALPrequentialEvaluationTask */
	public ClassOptionWithListenerOption learnerOption = 
			new ClassOptionWithListenerOption(
				"learner", 'l', "Learner to train.", ALClassifier.class, 
	            "moa.classifiers.active.ALZliobaite2011");
	
	public ClassOption streamOption = new ClassOption("stream", 's',
            "Stream to learn from.", ExampleStream.class,
            "generators.RandomTreeGenerator");
	
	public ClassOption prequentialEvaluatorOption = new ClassOption(
			"prequentialEvaluator", 'e',
            "Prequential classification performance evaluation method.",
            ALClassificationPerformanceEvaluator.class,
            "ALBasicClassificationPerformanceEvaluator");
	
	public IntOption instanceLimitOption = new IntOption("instanceLimit", 'i',
            "Maximum number of instances to test/train on  (-1 = no limit).",
            100000000, -1, Integer.MAX_VALUE);
	
	public IntOption timeLimitOption = new IntOption("timeLimit", 't',
            "Maximum number of seconds to test/train for (-1 = no limit).", -1,
            -1, Integer.MAX_VALUE);
	
	/* options used in in this class */
	public EditableMultiChoiceOption budgetParamNameOption = 
			new EditableMultiChoiceOption(
					"budgetParamName", 'p', 
					"Name of the parameter to be used as budget.",
					new String[]{"budget"}, 
					new String[]{"default budget parameter name"}, 
					0);
	
	public ListOption budgetsOption = new ListOption("budgets", 'b',
			"List of budgets to train classifiers for.",
			new FloatOption("budget", ' ', "Active learner budget.", 0.9), 
			new FloatOption[]{
					new FloatOption("", ' ', "", 0.5),
					new FloatOption("", ' ', "", 0.9)
			}, ',');
	
	public ClassOption multiBudgetEvaluatorOption = new ClassOption(
			"multiBudgetEvaluator", 'm',
            "Multi-budget classification performance evaluation method.",
            ALClassificationPerformanceEvaluator.class,
            "ALBasicClassificationPerformanceEvaluator");
	
	
	private ArrayList<ALPrequentialEvaluationTask> subtasks = new ArrayList<>();
	private ArrayList<ALTaskThread> subtaskThreads = new ArrayList<>();
	private ArrayList<ALTaskThread> flattenedSubtaskThreads = new ArrayList<>();
	
	
	public ALMultiBudgetTask() {
		super();
		
		// reset last learner option
		ALMultiBudgetTask.lastLearnerOption = null;
		
		// Enable refreshing the budgetParamNameOption depending on the
		// learnerOption
		this.learnerOption.setListener(new RefreshParamsChangeListener(
				this.learnerOption, this.budgetParamNameOption));
	}
	
	@Override
	public Options getOptions() {
		Options options = super.getOptions();
		
		// Get the initial values for the budgetParamNameOption
		ALMultiBudgetTask.refreshBudgetParamNameOption(
				this.learnerOption, this.budgetParamNameOption);
		
		return options;
	}
	
	@Override
	public Class<?> getTaskResultType() {
		return LearningCurve.class;
	}
	
	
	@Override
	protected void prepareForUseImpl(
			TaskMonitor monitor, ObjectRepository repository) 
	{
		super.prepareForUseImpl(monitor, repository);
		
		// get budget parameter name
		final String budgetParamName = 
				this.budgetParamNameOption.getValueAsCLIString();
		
		// get learner
		ALClassifier learner = 
				(ALClassifier) getPreparedClassOption(this.learnerOption);
		Option learnerBudgetOption = null;
		for (Option opt : learner.getOptions().getOptionArray()) {
			if (opt.getName().equals(budgetParamName)) {
				if (opt instanceof FloatOption || opt instanceof IntOption) {
					learnerBudgetOption = opt;
				}
				else {
					throw new IllegalArgumentException(
							"budgetParamName: Only numerical " +
							"attributes can be varied.");
				}
			}
		}
		
		// setup task for each budget
		Option[] budgets = this.budgetsOption.getList();
		for (int i = 0; i < budgets.length; i++) {
			
			// create subtask
			ALPrequentialEvaluationTask budgetTask = 
					new ALPrequentialEvaluationTask();
			budgetTask.setIsLastSubtaskOnLevel(
					this.isLastSubtaskOnLevel, i == budgets.length - 1);
			
			// set learner budget option
			learnerBudgetOption.setValueViaCLIString(
					budgets[i].getValueAsCLIString());
			
			for (Option opt : budgetTask.getOptions().getOptionArray()) {
				switch (opt.getName()) {
				case "learner":
					opt.setValueViaCLIString(ClassOption.objectToCLIString(
							learner, ALClassifier.class));
					break;
				case "stream": 
					opt.setValueViaCLIString(
							this.streamOption.getValueAsCLIString());
					break;
				case "prequential evaluator":
					opt.setValueViaCLIString(
							this.prequentialEvaluatorOption
							.getValueAsCLIString());
					break;
				case "instanceLimit":
					opt.setValueViaCLIString(
							this.instanceLimitOption.getValueAsCLIString());
					break;
				case "timeLimit":
					opt.setValueViaCLIString(
							this.timeLimitOption.getValueAsCLIString());
					break;
				}
			}
			
			budgetTask.prepareForUse();
			
			List<ALTaskThread> childSubtasks = budgetTask.getSubtaskThreads();
			
			// add new subtask and its thread to lists
			this.subtasks.add(budgetTask);
			
			ALTaskThread subtaskThread = new ALTaskThread(budgetTask);
			this.subtaskThreads.add(subtaskThread);

			this.flattenedSubtaskThreads.add(subtaskThread);
			this.flattenedSubtaskThreads.addAll(childSubtasks);
		}
	}
	
	@Override
	protected Object doMainTask(
			TaskMonitor monitor, ObjectRepository repository) 
	{
		// setup learning curve
		PreviewCollection<PreviewCollectionLearningCurveWrapper> 
			previewCollection = new PreviewCollection<>(
					"multi budget entry id", "learner id", this.getClass());		
		// start subtasks
		monitor.setCurrentActivity("Evaluating learners for budgets...", -1.0);
		for(int i = 0; i < this.subtaskThreads.size(); ++i)
		{
			subtaskThreads.get(i).start();
		}

		// get the number of subtask threads
		int numSubtaskThreads = subtaskThreads.size();
		// check the previews of subtaskthreads
		boolean allThreadsCompleted = false;
		// iterate while there are threads active
		while(!allThreadsCompleted)
		{
			allThreadsCompleted = true;
			int oldNumEntries = previewCollection.numEntries();
			double completionSum = 0;
			// iterate over all threads
			for(int i = 0; i < numSubtaskThreads; ++i)
			{
				ALTaskThread currentTaskThread = subtaskThreads.get(i);
				// check if the thread is completed
				allThreadsCompleted &= currentTaskThread.isComplete();
				// get the completion fraction
				completionSum += currentTaskThread.getCurrentActivityFracComplete();
				// get the latest preview
				PreviewCollectionLearningCurveWrapper latestPreview = 
						(PreviewCollectionLearningCurveWrapper) 
						currentTaskThread.getLatestResultPreview();
				// ignore the preview if it is null
				if(latestPreview != null && latestPreview.numEntries() > 0)
				{	
					// update/add the learning curve to the learning curve collection
					previewCollection.setPreview(i, latestPreview);
				}
				else
				{
					// skip for loop until all threads before were at least added once
					break;
				}
			}
			double completionFraction = completionSum / numSubtaskThreads;
			
			monitor.setCurrentActivityFractionComplete(completionFraction);
			
			// check if the task should abort or paused
    		if (monitor.taskShouldAbort()) {
                return null;
            }
			
			// check if the preview has actually changed
			if(oldNumEntries < previewCollection.numEntries())
			{
				// check if a preview is requested
	    		if (monitor.resultPreviewRequested() || isSubtask()) {
	    			// send the latest preview to the monitor
	                monitor.setLatestResultPreview(previewCollection.copy());

	        		monitor.setCurrentActivityFractionComplete(-1.0);
	            }
			}
		}
		
		return previewCollection;
	}
	
	@Override
	public List<ALTaskThread> getSubtaskThreads() {
		return this.flattenedSubtaskThreads;
	}
	
	
	
	/* Static classes and methods */
	
	protected static String lastLearnerOption;
	
	protected static class RefreshParamsChangeListener 
		implements ChangeListener, Serializable 
	{
		
		private static final long serialVersionUID = 1L;
		
		private ClassOption learnerOption;
		private EditableMultiChoiceOption budgetParamNameOption;
		
		public RefreshParamsChangeListener(
				ClassOption learnerOption, 
				EditableMultiChoiceOption budgetParamNameOption) 
		{
			this.learnerOption = learnerOption;
			this.budgetParamNameOption = budgetParamNameOption;
		}
		
		@Override
		public void stateChanged(ChangeEvent e) {
			ALMultiBudgetTask.refreshBudgetParamNameOption(
					this.learnerOption, this.budgetParamNameOption);
		}
	}
	
	protected static void refreshBudgetParamNameOption(
			ClassOption learnerOption, 
			EditableMultiChoiceOption budgetParamNameOption)
	{
		ALClassifier learner = 
				(ALClassifier) learnerOption.getPreMaterializedObject();
		String currentLearner = learner.getClass().getSimpleName();
		
		// check if an update is actually needed
		if (lastLearnerOption == null || 
			!lastLearnerOption.equals(currentLearner)) 
		{
			lastLearnerOption = currentLearner;
			
			Option[] options = learner.getOptions().getOptionArray();
			String[] optionNames = new String[options.length];
			String[] optionDescriptions = new String[options.length];
			int defaultIndex = -1;
			
			for (int i = 0; i < options.length; i++) {
				optionNames[i] = options[i].getName();
				optionDescriptions[i] = options[i].getPurpose();
				
				if (optionNames[i].equals("budget") || 
					(optionNames[i].contains("budget") && defaultIndex < 0)) 
				{
					defaultIndex = i;
				}
			}
			
			budgetParamNameOption.setOptions(optionNames, optionDescriptions, 
					defaultIndex >= 0 ? defaultIndex : 0);
		}
	}
}
