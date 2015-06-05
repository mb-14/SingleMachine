package org.isw;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

public class Schedule implements  Comparable<Schedule>,Serializable{

	
	private static final long serialVersionUID = 1L;
	private ArrayList<Job> jobs;
	private long sum;
	InetAddress ip;

	public Schedule(){
		sum = 0;
		jobs = new ArrayList<Job>();
	}
	
	public Schedule(Schedule source){
		sum = source.sum;
		jobs =  new ArrayList<Job>();
		for(int i =0; i<source.jobs.size();i++){
			jobs.add(new Job(source.jobAt(i)));
		}		
	}
	
	public Schedule(InetAddress byName) {
		//check this
		sum = 0;
		jobs = new ArrayList<Job>();
	}

	public void addJob(Job job){
		jobs.add(job);
		sum+=job.getJobTime();
	}
	
	public int numOfJobs()
	{
		return jobs.size();
	}
	/**
	 * Add CM Job. If a CM job overlaps with a normal job, split the normal job and insert CM job 
	 * in between. If a CM job overlaps with a PM/CM job, add it after the CM/PM job.
	**/
	public void addCMJob(Job cmJob, long TTF){
		if (TTF >= sum)
			return;
		int i = jobIndexAt(TTF);
		long time = getFinishingTime(i);
		
		if(jobs.get(i).getJobType() == Job.JOB_NORMAL){
				Job job  = jobs.remove(i);
				time -= job.getJobTime();
				Job j1 =  new Job(job.getJobName(),TTF-time,job.getJobCost(),Job.JOB_NORMAL);
				Job j2 = new Job(job.getJobName(),time+job.getJobTime()-TTF,job.getJobCost(),Job.JOB_NORMAL);
				if(TTF == time){
					jobs.add(i,cmJob);
					jobs.add(i+1,j2);
				}
				else{
					jobs.add(i,j1);
					jobs.add(i+1,cmJob);
					jobs.add(i+2,j2);
				}
			}
			else{
				while(i< jobs.size() && jobs.get(i).getJobType() == Job.JOB_PM)
					i++;
				jobs.add(i,cmJob);
			}
		sum += cmJob.getJobTime();
	}
	//Insert PM job at give opportunity.
	public void addPMJob(Job pmJob, int opportunity){
		jobs.add(opportunity, pmJob);
		sum += pmJob.getJobTime();
	}
	
	public synchronized Job remove() throws IOException{
		Job job = jobs.remove(0);
		sum -= job.getJobTime();
		if(sum < 0){
			throw new IOException();
		}
		return job;
	}
	
	@Override
	public int compareTo(Schedule other) {
		return Long.compare(this.getSum(), other.getSum());
	}

	public synchronized boolean isEmpty() {
		return jobs.isEmpty();
	}
	
	public synchronized Job peek(){
		return jobs.get(0);
	}
	
	public synchronized void decrement(long delta) throws IOException{
		jobs.get(0).decrement(delta);
		sum -= delta;
		if(sum < 0){
			throw new IOException();
		}
	}

	public String printSchedule() {
		String str="";
		for(int i=0;i<jobs.size();i++)
			str += jobs.get(i).getJobName()+": "+ String.valueOf(jobs.get(i).getJobTime()/Macros.TIME_SCALE_FACTOR)+"hrs ";			
		return str;
	}
	
	//check these two
	public InetAddress getAddress() {
		// TODO Auto-generated method stub
		return ip;
	}

	public void setAddress(InetAddress ip) {
		this.ip = ip;
		
	}

	public long getSum() {
		return sum;
	}

	public void setSum(long sum) {
		this.sum = sum;
	}

	public int jobIndexAt(long time) {
		int temp =0;
		int i = 0;
		while(temp<=time){
			temp+= jobs.get(i).getJobTime();
			i++;
		}
		return i-1;
	}

	public void addWaitJob(long startTime, long waitTime, int jobIndex) {
		Job job = jobs.remove(jobIndex);
		long time = getFinishingTime(jobIndex-1);
		System.out.println(startTime +":"+time);
		Job job1 = new Job(job.getJobName(),startTime - time,job.getJobCost(),job.getJobType());
		job1.setFixedCost(job.getFixedCost());
		Job waitJob = new Job("Waiting",waitTime,0,Job.WAIT_FOR_MT);
		Job job2 = new Job(job.getJobName(),time+job.getJobTime()-startTime,job.getJobCost(),job.getJobType());
		if(startTime == time){
		jobs.add(jobIndex,waitJob);
		job2.setFixedCost(job.getFixedCost());
		jobs.add(jobIndex+1,job2);
		}
		else{
			jobs.add(jobIndex,job1);
			jobs.add(jobIndex+1,waitJob);
			jobs.add(jobIndex+2,job2);
		}
		sum += waitTime;
	}

	public long getFinishingTime(int index){
		long sum = 0;
		int i =0;
		while(i <= index){
			sum+= jobs.get(i).getJobTime();
			i++;
		}
		return sum;
	}
	
	public Job jobAt(int i) {
		return jobs.get(i);
	}
	public int indexOf(Job job){
		return jobs.indexOf(job);
	}
	public static Schedule receive(ServerSocket tcpSocket)
	{
		//uses TCP to receive Schedule
		Schedule ret = null;
		try
		{
			Socket tcpSchedSock = tcpSocket.accept();
			ObjectInputStream ois = new ObjectInputStream(tcpSchedSock.getInputStream());
			Object o = ois.readObject();

			if(o instanceof Schedule) 
			{

				ret = (Schedule)o;
			}
			else 
			{
				System.out.println("Received Schedule is garbled");
			}
			ois.close();
			tcpSchedSock.close();
		}catch(Exception e)
		{
			System.out.println("Failed to receive schedule.");
		}

		return ret;
	}

	public int getSize() {
		return jobs.size();
	}
	public ArrayList<Integer> getPMOpportunities(){
		ArrayList<Integer> arr = new ArrayList<Integer>();
		if(jobs.isEmpty())
			return arr;
		
		if(jobs.get(0).getJobType() == Job.JOB_NORMAL)
			arr.add(0);
		int i=1;
		while(i<jobs.size()){
			Job current = jobs.get(i);
			if(current.getJobType() == Job.JOB_NORMAL && getFinishingTime(i-1) < Macros.SHIFT_DURATION*Macros.TIME_SCALE_FACTOR)
				arr.add(i);
			i++;
		}
		if(getFinishingTime(i-1) < Macros.SHIFT_DURATION*Macros.TIME_SCALE_FACTOR)
			arr.add(i);
		return arr;
	}

	public Job remove(int index) throws IOException {
		Job job = jobs.remove(index);
		sum -= job.getJobTime();
		if(sum < 0){
			throw new IOException();
		}
		return job;
		
	}

	public ArrayList<Job> getPMJobs() {
		ArrayList<Job> pmJobs = new ArrayList<Job>();
		for(int i = jobs.size()-1; i>0; i--){
				if(jobs.get(i).jobType == Job.JOB_PM)
					pmJobs.add(jobs.get(i));
		}
		return pmJobs;
	}
}