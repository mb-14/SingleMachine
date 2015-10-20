package org.isw;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class Main {
	public static ArrayList<Schedule> mainSchedules = new ArrayList<Schedule>();
	public static ArrayList<Schedule> minSchedules = new ArrayList<Schedule>();
	static ArrayList<ArrayList<SimulationResult>> table;
	static Random r = new Random(); 
	static ArrayList<Job> jobArray;
	static ArrayList<Machine> machines = new ArrayList<Machine>();
	static int noOfMachines =0;
	static LabourAvailability pmLabourAssignment;
	public static AtomicBoolean labour;
	static Double minCost;
	static double runTime;
	public static void main(String args[]) throws InterruptedException, ExecutionException
	{
		Macros.loadMacros();
		
		System.out.println("Enter number of days to simulate:");
		Scanner in = new Scanner(System.in);
		int shiftCount = in.nextInt()*24/Macros.SHIFT_DURATION;
		System.out.println("Enter number of machines:");
		noOfMachines = in.nextInt();
		for(int i=0;i<noOfMachines;i++){
			System.out.println("Enter no of components of machine "+(i+1)+":");
			int n = in.nextInt();
			int compIndex[] = new int[n];
			System.out.println("Enter component indices:");
			for(int j=0;j<n;j++){
				compIndex[j] = in.nextInt();
			}
			machines.add(new Machine(i,compIndex));
			mainSchedules.add(new Schedule());
			minSchedules.add(mainSchedules.get(i));
		}
		in.close();
		parseJobs();
		minCost = Double.MAX_VALUE;
		int shiftNo = 0;
		//Main loop
		while(shiftNo++ < shiftCount){
			PriorityQueue<Schedule> pq = new PriorityQueue<Schedule>();
			for(Schedule sched : mainSchedules)
				pq.add(sched);					
			
			System.out.println("Job list: ");
			for(int i=0;i<jobArray.size();i++){
				Schedule min = pq.remove();
				min.addJob(new Job(jobArray.get(i)));
				System.out.print(jobArray.get(i).getJobName()+": "+String.valueOf(jobArray.get(i).getJobTime()/Macros.TIME_SCALE_FACTOR)+" ");
				pq.add(min);
			}
			System.out.println("");
			ExecutorService threadPool = Executors.newFixedThreadPool(noOfMachines);
			CompletionService<ArrayList<SimulationResult>> pool = new ExecutorCompletionService<ArrayList<SimulationResult>>(threadPool);
			int cnt=0;
			
			table = new ArrayList<ArrayList<SimulationResult>>();
			
			
			System.out.println("Planning...");
			long startTime = System.nanoTime();
			while(!pq.isEmpty()){
				cnt++;
				Schedule sched = pq.poll();
				pool.submit(new MachineThread(sched,mainSchedules.indexOf(sched)));
			}
			for(int i=0;i<cnt;i++){
				ArrayList<SimulationResult> results  = pool.take().get();	
				for(int j=0;j<results.size();j++)
				{
						for(int pmOpp=0; pmOpp < results.get(j).pmOpportunity.size(); pmOpp++)
						{
							// calculate start times for each job in SimulationResult
							if(results.get(j).pmOpportunity.get(pmOpp) <= 0){
								results.get(j).startTimes[pmOpp] = 0; //assign calculated t
							}
							else{
								// start time of PM job is finishing time of job before it
								results.get(j).startTimes[pmOpp] = mainSchedules.get(results.get(j).id).getFinishingTime(results.get(j).pmOpportunity.get(pmOpp)-1);
							}
						}
					
				}
				
				if(results.size() > 0)
				{	
					System.out.println("Machine "+(results.get(0).id+1)+": Number of PM schedules:"+results.size());
					table.add(results);
				
				}
			}
			Collections.sort(table, new MachineComparator());
			threadPool.shutdown();
			while(!threadPool.isTerminated());
			pmLabourAssignment = new LabourAvailability(new int[]{1,0,0}, Macros.SHIFT_DURATION*Macros.TIME_SCALE_FACTOR);
			for(int i=0; i<mainSchedules.size(); i++)
			{
				Schedule sched = mainSchedules.get(i);
				if(!sched.isEmpty() && sched.jobAt(0).getJobType()==Job.JOB_PM)
				{
					//pending PM job present in this schedule
					System.out.println("Reserving Labour for previous shift PM job");
					//reserve labour for it
					pmLabourAssignment.employLabour(0, sched.jobAt(0).getSeriesTTR(), sched.jobAt(0).getSeriesLabour());
				}
			}
			System.out.println("Calculating permutations");
			if(table.size() > 0)
				for(int i=0;i<table.get(0).size();i++)
					calculatePermutations(table.get(0).get(i), 0 , pmLabourAssignment); 
			
			runTime = (System.nanoTime() - startTime)/Math.pow(10, 9);
			mainSchedules = minSchedules;
			for(int i=0;i<Macros.SIMULATION_COUNT;i++)
				calculateCost(false);
		}	
		System.out.format("Planning time: %f\n",runTime);
		for(Machine machine : machines)
			writeResults(machine,Macros.SIMULATION_COUNT);
	}
	
	private static void calculatePermutations(SimulationResult row, int level, LabourAvailability pmLabourAssignment) throws InterruptedException, ExecutionException {
		//pmLabourAssignment.print();
		Schedule temp = new Schedule(mainSchedules.get(row.id));
		LabourAvailability tempLabour = new LabourAvailability(pmLabourAssignment);
		assignLabour(row,tempLabour);
		if(level == table.size()-1){
			calculateCost(true);
		}
		else {
			for(int j=0;j<table.get(level+1).size(); j++){
				calculatePermutations(table.get(level+1).get(j),level+1,pmLabourAssignment);
			}
		}
		mainSchedules.set(row.id, temp);
	}
	private static void calculateCost(boolean isPlanning) throws InterruptedException, ExecutionException {
		ExecutorService threadPool = Executors.newFixedThreadPool(noOfMachines);
		CompletionService<Double> pool = new ExecutorCompletionService<Double>(threadPool);
		CyclicBarrier sync = new CyclicBarrier(noOfMachines);
		labour = new AtomicBoolean(true);
		for(int i=0;i<noOfMachines;i++){
			pool.submit(new JobExecThread(mainSchedules.get(i),machines.get(i),isPlanning,sync));
		}
		Double cost = 0d;

		for(int i=0;i<noOfMachines;i++){
			cost += pool.take().get();
		}
		threadPool.shutdown();
		while(!threadPool.isTerminated());
		if(isPlanning){
			if(cost < minCost){
				minCost = cost;
				for(int j=0;j<noOfMachines;j++)
					minSchedules.set(j,new Schedule(mainSchedules.get(j)));
			}
		}
	}
	
	static void assignLabour(SimulationResult row, LabourAvailability pmLabour){
		Component[] compList = machines.get(row.id).compList;
		row.pmTTRs = new long[row.pmOpportunity.size()][compList.length];
		
		// check if schedule is empty
		if(!mainSchedules.get(row.id).isEmpty())
		{
			
			// generate PM TTRs of all components undergoing PM for this row
			boolean meetsReqForAllOpp = true;
			int[][] seriesLabour = new int[row.pmOpportunity.size()][3];
			long[] seriesTTR = new long[row.pmOpportunity.size()];
			for(int pmOpp = 0; pmOpp<row.pmOpportunity.size(); pmOpp++)
			{					
				seriesLabour[pmOpp][0] = 0;
				seriesLabour[pmOpp][1] = 0;
				seriesLabour[pmOpp][2] = 0;
				seriesTTR[pmOpp] = 0;
				for(int compno=0;compno<compList.length;compno++)
				{
					int pos = 1<<compno;
					if((pos&row.compCombo[pmOpp])!=0) //for each component in combo, generate TTR
					{
						row.pmTTRs[pmOpp][compno] = Component.notZero(compList[compno].getPMTTR()*Macros.TIME_SCALE_FACTOR); //store PM TTR
						seriesTTR[pmOpp] += row.pmTTRs[pmOpp][compno];
						
						// find max labour requirement for PM series
						int[] labour1 = compList[compno].getPMLabour();
						
						if(seriesLabour[pmOpp][0] < labour1[0])
							seriesLabour[pmOpp][0] = labour1[0];
						if(seriesLabour[pmOpp][1] < labour1[1])
							seriesLabour[pmOpp][1] = labour1[1];
						if(seriesLabour[pmOpp][2] < labour1[2])
							seriesLabour[pmOpp][2] = labour1[2];
					}
					
				}
				if(!pmLabour.checkAvailability(row.startTimes[pmOpp], row.startTimes[pmOpp]+seriesTTR[pmOpp], seriesLabour[pmOpp]))
				{
					// add series of PM jobs at that opportunity.
					meetsReqForAllOpp = false;
					break;
				}
			}
			
			if(meetsReqForAllOpp)
			{
				
				//incorporate the PM job(s) into schedule of machine
				addPMJobs(mainSchedules.get(row.id), machines.get(row.id).compList, row, seriesTTR, seriesLabour);
				//reserve labour
				for(int pmOpp = 0; pmOpp<row.pmOpportunity.size(); pmOpp++)
					pmLabour.employLabour(row.startTimes[pmOpp], row.startTimes[pmOpp]+seriesTTR[pmOpp], seriesLabour[pmOpp]);
			}
		}
	}
	private static void addPMJobs(Schedule schedule,Component[] compList, SimulationResult row, long[] seriesTTR, int[][] seriesLabour) {
		/*
		 * Add PM jobs to given schedule.
		 */
		int cnt = 0;
		ArrayList<Integer> pmOpportunity = row.pmOpportunity;
		long[] compCombo = row.compCombo;
		
		for(int pmOpp = 0; pmOpp<pmOpportunity.size(); pmOpp++)
		{
			for(int i=0;i< compList.length;i++)
			{
				int pos = 1<<i;
				if((pos&compCombo[pmOpp])!=0) //for each component in combo, add a PM job
				{
					long pmttr = Component.notZero(row.pmTTRs[pmOpp][i]);
					
					Job pmJob = new Job("PM",pmttr,compList[i].getPMLabourCost(),Job.JOB_PM);
					pmJob.setCompNo(i);
					pmJob.setSeriesTTR(seriesTTR[pmOpp]);
					pmJob.setSeriesLabour(seriesLabour[pmOpp]);
					if(cnt==0){
						// consider fixed cost only once, for the first job
						pmJob.setFixedCost(compList[i].getPMFixedCost());
					}
					
					// add job to schedule
					schedule.addPMJob(new Job(pmJob),pmOpportunity.get(pmOpp)+cnt);

					cnt++;
				}
			}
		}
	}
	private static void parseJobs() 
	{
		/*
		 * Get jobs from Excel sheet
		 */
		jobArray = new ArrayList<Job>();
		try
		{
			FileInputStream file = new FileInputStream(new File("Jobs.xlsx"));
			XSSFWorkbook workbook = new XSSFWorkbook(file);
			XSSFSheet sheet = workbook.getSheetAt(0);

			for(int i=1;i<=noOfMachines*3;i++)
			{
				Row row = sheet.getRow(i);
				int demand = (int) row.getCell(5).getNumericCellValue();
				String jobName = row.getCell(0).getStringCellValue();
				long jobTime = (long)(row.getCell(1).getNumericCellValue()*Macros.TIME_SCALE_FACTOR);
				double jobCost = row.getCell(3).getNumericCellValue();
				for(int j=0; j<demand ;j++){
					Job job = new Job(jobName,jobTime,jobCost,Job.JOB_NORMAL);
					job.setPenaltyCost(row.getCell(4).getNumericCellValue());
					jobArray.add(job);
				}
			}
			file.close();
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}	
		//sort jobs in descending order of job time

		Collections.sort(jobArray, new JobComparator());

	}


	private static void writeResults(Machine machine, int simCount) {
		double cost = machine.cmCost + machine.pmCost + machine.penaltyCost;
		double downtime = (machine.cmDownTime + machine.pmDownTime + machine.waitTime)/simCount;
		double runtime = 1440 - machine.idleTime/simCount;
		double availability = 100  - 100*downtime/runtime;
		System.out.println("=========================================");
		System.out.println("Machine "+ (machine.machineNo+1));
		System.out.format("%f| %f \n",availability,cost/simCount);
		//System.out.println("Downtime:" + String.valueOf(machine.downTime*100/(machine.runTime)) +"%");
		System.out.println("CMDowntime: "+ machine.cmDownTime/simCount +" hours");
		System.out.println("PM Downtime: "+ machine.pmDownTime/simCount +" hours");
		System.out.println("Waiting Downtime: "+ machine.waitTime/simCount +" hours");
		System.out.println("Machine Idle time: "+ machine.idleTime/simCount+" hours");
		System.out.println("PM Cost: "+ machine.pmCost/simCount);
		System.out.println("CM Cost: "+ machine.cmCost/simCount);
		System.out.println("Penalty Cost: "+ machine.penaltyCost/simCount);
		System.out.println("Processing Cost: "+ machine.procCost/simCount);
		System.out.println("Number of jobs:" + (double)machine.jobsDone/simCount);
		System.out.println("Number of CM jobs:" + (double)machine.cmJobsDone/simCount);
		System.out.println("Number of PM jobs:" + (double)machine.pmJobsDone/simCount);
		for(int i=0 ;i<machine.compList.length; i++)
			System.out.println("Component "+machine.compList[i].compName+": PM "+(double)machine.compPMJobsDone[i]/simCount+"|CM "+(double)machine.compCMJobsDone[i]/simCount);
		
	}
	
}
class JobComparator implements Comparator<Job> {
	@Override
	public int compare(Job a, Job b) 
	{

			return Long.compare(b.getJobTime(),a.getJobTime()); 
	}	

}