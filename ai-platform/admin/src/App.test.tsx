import {describe,expect,it,vi,beforeEach,afterEach} from 'vitest';
import {render,screen,waitFor} from '@testing-library/react';
import {Approvals} from './engineering';
import * as apiModule from './api';

describe('engineering approvals panel',()=>{
  beforeEach(()=>{vi.useFakeTimers({shouldAdvanceTime:true})});
  afterEach(()=>{vi.useRealTimers();vi.restoreAllMocks()});

  it('renders the empty state when no run is paused',async()=>{
    vi.spyOn(apiModule,'api').mockResolvedValue({items:[]} as never);
    render(<Approvals/>);
    await waitFor(()=>expect(screen.getByText(/No run is waiting/i)).toBeTruthy());
  });

  it('offers approve and reject for a pending command',async()=>{
    vi.spyOn(apiModule,'api').mockResolvedValue({items:[{
      id:'a1',run_id:'r1',task_id:'t1',kind:'command_execution',
      reason:'Agent requested isolated command: [\'pytest\']',status:'pending',
      requested_by_agent:'implementer',created_at:new Date().toISOString(),decided_at:null,
    }]} as never);
    render(<Approvals/>);
    await waitFor(()=>expect(screen.getByRole('button',{name:'Approve'})).toBeTruthy());
    expect(screen.getByRole('button',{name:'Reject'})).toBeTruthy();
    expect(screen.getByText(/implementer/)).toBeTruthy();
  });
});

describe('admin runtime contract',()=>{
  it('exposes Web Crypto UUID generation used for request correlation',()=>{
    expect(typeof crypto.randomUUID).toBe('function');
  });
});
