package org.isw;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.concurrent.Callable;

public class SimulationThread implements Callable<SimulationResult> {
	Schedule schedule; // Job Schedule received by scheduler
	long compCombo[]; //Combination of components to perform PM on.
	ArrayList<Integer> pmOpportunity;
	Component[] simCompList;
	int noOfSimulations = Macros.SIMULATION_COUNT;
	boolean noPM;
	Machine machine;
	public SimulationThread(Schedule schedule, long compCombo[], ArrayList<Integer> pmOpportunity,boolean noPM, Machine machine){
		this.schedule = schedule;
		this.compCombo = compCombo;
		this.pmOpportunity = pmOpportunity;
		this.noPM = noPM;
		this.machine = machine;
	}
	/**
	 * We shall run the simulation 1000 times,each simulation being Macros.SHIFT_DURATION hours (real time) in duration.
	 * For each simulation PM is done only once and is carried out in between job executions.
	 * @throws IOException 
	 * **/
	public SimulationResult call(){
		double totalCost = 0;
		double pmAvgTime = 0;
		int cnt = 0;
		int cmJobs = 0;
		int pmJobs = 0;
		double runTime = 0;
		while(cnt++ < noOfSimulations){
			double pmCost = 0;   //PM cost 
			double cmCost = 0;   //CM cost
			double penaltyCost = 0; //Penalty cost
			Schedule simSchedule = new Schedule(schedule);
			simCompList = new Component[machine.compList.length];
			for(int i=0;i< machine.compList.length;i++)
				simCompList[i] = new Component(machine.compList[i]);
			/*Add PM job to the schedule*/
			if(!noPM){
				addPMJobs(simSchedule,simCompList);
			}
			// find all machine failures and CM times for this shift
			LinkedList<FailureEvent> failureEvents = new LinkedList<FailureEvent>();
			FailureEvent upcomingFailure = null;
			for(int compNo=0; compNo<simCompList.length; compNo++)
			{
				//System.out.format("%d %f %f %f\n",compNo,simCompList[compNo].cmEta,simCompList[compNo].cmBeta,simCompList[compNo].initAge);
				long ft = (long)(simCompList[compNo].getCMTTF());
				if(ft < Macros.SHIFT_DURATION)
				{
					// this component fails in this shift
					failureEvents.add(new FailureEvent(compNo, ft*Macros.TIME_SCALE_FACTOR));
				}
			}
			if(!failureEvents.isEmpty())
			{
				Collections.sort(failureEvents, new FailureEventComparator());
				upcomingFailure =  failureEvents.pop();
			}

			long time = 0;
			while(time < Macros.SHIFT_DURATION*Macros.TIME_SCALE_FACTOR && !simSchedule.isEmpty()){
				//Calculate the cost depending upon the job type
				Job current = simSchedule.peek(); 
				//debug2 += String.format("%s: %d\n", current.getJobName(),current.getJobTime()); 
				//System.out.println(current.getJobName()+":"+time);
				if(current.getJobType()!= Job.JOB_CM&&current.getJobType()!= Job.JOB_PM && upcomingFailure!=null && time == upcomingFailure.failureTime)
				{
					Job cmJob = new Job("CM", upcomingFailure.repairTime, simCompList[upcomingFailure.compNo].getCMLabourCost(), Job.JOB_CM);
					cmJob.setFixedCost(simCompList[upcomingFailure.compNo].getCMFixedCost());
					cmJob.setCompNo(upcomingFailure.compNo);
					simSchedule.addJobTop(cmJob);
					current = simSchedule.peek();
				}
				
				if(current.getJobType() == Job.JOB_NORMAL){
					for(Component comp : simCompList)
						comp.initAge++;
					runTime++;
				}
				else if(current.getJobType() == Job.JOB_PM){
					pmCost += current.getFixedCost() + current.getJobCost()/Macros.TIME_SCALE_FACTOR;
					current.setFixedCost(0);
					pmAvgTime += 1;
				}
				else if(current.getJobType() == Job.JOB_CM){
					cmCost += current.getFixedCost() + current.getJobCost()/Macros.TIME_SCALE_FACTOR;
					current.setFixedCost(0);
				}
				// decrement job time by unit time
				try {
				simSchedule.decrement(1);
				}catch (IOException e){
				
				}
				time++;
				
				if(current.getJobTime()<=0){
					switch(current.getJobType())
					{
					case Job.JOB_NORMAL:
						break;
					case Job.JOB_PM:
						pmJobs++;
						Component comp1 = simCompList[current.getCompNo()];
						comp1.initAge = (1-comp1.pmRF)*comp1.initAge;
						// recompute component failures
						failureEvents = new LinkedList<FailureEvent>();
						upcomingFailure = null;
						for(int compNo=0; compNo<simCompList.length; compNo++)
						{
							long ft = time + (long) simCompList[compNo].getCMTTF()*Macros.TIME_SCALE_FACTOR;
							if(ft < Macros.SHIFT_DURATION*Macros.TIME_SCALE_FACTOR)
							{
								// this component fails in this shift
								failureEvents.add(new FailureEvent(compNo, ft));
							}
						}

						if(!failureEvents.isEmpty())
						{
							Collections.sort(failureEvents, new FailureEventComparator());
							upcomingFailure =  failureEvents.pop();
						}
						break;
					case Job.JOB_CM:
						cmJobs++;
						Component comp = simCompList[current.getCompNo()];
						comp.initAge = (1 - comp.cmRF)*comp.initAge;
						// recompute component failures
						failureEvents = new LinkedList<FailureEvent>();
						upcomingFailure = null;
						for(int compNo=0; compNo<simCompList.length; compNo++)
						{
							long ft = time + (long) simCompList[compNo].getCMTTF()*Macros.TIME_SCALE_FACTOR;
							if(ft < Macros.SHIFT_DURATION*Macros.TIME_SCALE_FACTOR)
							{
								// this component fails in this shift
								failureEvents.add(new FailureEvent(compNo, ft));
							}
						}
						if(!failureEvents.isEmpty())
						{
							Collections.sort(failureEvents, new FailureEventComparator());
							upcomingFailure =  failureEvents.pop();
						}
						break;
					}
					
						try {
							simSchedule.remove();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
							
						}
				}
			}
			try{
				//Calculate penaltyCost for leftover jobs
				while(!simSchedule.isEmpty()){
					Job job = simSchedule.remove();
					switch(job.getJobType()){
						case Job.JOB_NORMAL:	
							penaltyCost += job.getPenaltyCost()*job.getJobTime();
						break;
						case Job.JOB_PM:
							pmCost += job.getFixedCost() + job.getJobCost()*job.getJobTime()*Macros.TIME_SCALE_FACTOR; 
							pmAvgTime += job.getJobTime();
							break;
						case Job.JOB_CM:
							cmCost += job.getFixedCost() + job.getJobCost()*job.getJobTime()*Macros.TIME_SCALE_FACTOR; 
					}
					
				
				}
			}
			catch(IOException e){
				e.printStackTrace();
				System.exit(0);
			}
			//Calculate totalCost for the shift
			totalCost +=  pmCost + cmCost + penaltyCost;

		}
		//System.out.format("CM:%d PM:%d\n",cmJobs/1000,pmJobs/1000);
		//System.out.format("Availability: %f \n",runTime/14400);
		totalCost /= noOfSimulations;
		pmAvgTime /= noOfSimulations;
		return new SimulationResult(totalCost,pmAvgTime,compCombo,pmOpportunity,noPM,Main.machines.indexOf(machine));
	}
	/*Add PM job for the given combination of components.
	 * The PM jobs are being split into smaller PM jobs for each component.
	 * But the fixed PM cost is only added for the first job to meet the cost model requirements.
	 * */
	private void addPMJobs(Schedule simSchedule,Component[] simCompList) {
		int cnt = 0;
		for(int pmOppo=0; pmOppo < pmOpportunity.size(); pmOppo++){
		for(int i=0;i<simCompList.length;i++){
			int pos = 1<<i;
			if((pos&compCombo[pmOppo])!=0){
				long pmttr = Component.notZero((simCompList[i].getPMTTR()*Macros.TIME_SCALE_FACTOR));
				Job pmJob = new Job("PM",pmttr,simCompList[i].getPMLabourCost(),Job.JOB_PM);
				pmJob.setCompNo(i);
				if(cnt==0){
					pmJob.setFixedCost(simCompList[i].getPMFixedCost());
				}
				simSchedule.addPMJob(pmJob,pmOpportunity.get(pmOppo)+cnt);
				cnt++;
			}
			}
		}
	}



	class FailureEvent
	{
		public int compNo;
		public long repairTime;
		public long failureTime;

		public FailureEvent(int compNo, long failureTime)
		{
			this.compNo = compNo;
			this.repairTime = Component.notZero(simCompList[compNo].getCMTTR()*Macros.TIME_SCALE_FACTOR);
			this.failureTime = failureTime;
		}
	}

	class FailureEventComparator implements Comparator<FailureEvent> {
		/*
		 * Sort events in ascending order of failure time
		 */
		@Override
		public int compare(FailureEvent a, FailureEvent b) 
		{
			return Long.compare(a.failureTime,b.failureTime);
		}

	}
}
