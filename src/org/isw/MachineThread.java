package org.isw;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MachineThread implements Callable<ArrayList<SimulationResult>> {
	Schedule schedule;
	Machine machine;
	ArrayList<Integer> pmoList;
	
	public MachineThread(Schedule schedule, int machineNo){
		this.schedule = schedule;
		this.machine = Main.machines.get(machineNo);
	}
	@Override
	public ArrayList<SimulationResult> call() throws Exception {
		System.out.println("Machine "+ (machine.machineNo+1)+ ": "+ schedule.printSchedule());
		pmoList = schedule.getPMOpportunities();
		ExecutorService threadPool = Executors.newSingleThreadExecutor();
		CompletionService<SimulationResult> pool = new ExecutorCompletionService<SimulationResult>(threadPool);
		pool.submit(new SimulationThread(schedule,null,null,true,machine));
		SimulationResult noPM = pool.take().get();
		threadPool.shutdown();
		while(!threadPool.isTerminated());
		ArrayList<SimulationResult> results = new ArrayList<>();
		if(pmoList.isEmpty()){
			return results;
		}

		threadPool = Executors.newFixedThreadPool(20);
		pool = new ExecutorCompletionService<SimulationResult>(threadPool);
		int cnt=0;
		double max = Math.pow(2, machine.compList.length*pmoList.size());
		for(int i = 1;i<max;i++){
				pool.submit(new SimulationThread(schedule,getCombolist(i),pmoList,false,machine));
				cnt++;
		}
		for(int i=0;i<cnt;i++)
		{
			SimulationResult result = pool.take().get();
			if(noPM.cost > result.cost){
				result.cost = noPM.cost - result.cost;
				results.add(result);
				}
		}	
		results.add(new SimulationResult(0,0,getCombolist(0),pmoList,true,Main.machines.indexOf(machine)));
		threadPool.shutdown();
		while(!threadPool.isTerminated());
		return results;
	}
	
	private long[] getCombolist(long combo) {
		long combos[] = new long[pmoList.size()];
		for(int i =0;i<pmoList.size();i++){
			combos[i] = (combo>>(machine.compList.length*i))&((int)Math.pow(2,machine.compList.length)-1);
		}
		return combos;
	}

}
