import {setRuntimeConfigOverrides} from '@/app/config'
import {listWorkersReal} from '@/api/workers.real'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('workers.real', () => {
    it('preserves backend worker field source labels', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
        })
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    items: [
                        {
                            workerId: 'worker-001',
                            status: 'ONLINE',
                            transportReachability: 'ONLINE',
                            transportOnline: true,
                            workerGroupId: 'group-a',
                            adapterNodeId: 'node-a',
                            agentVersion: '1.2.3',
                            supportedProjects: ['demoApp'],
                            supportedEventCodes: ['demo.dispatch'],
                            attributes: {region: 'us'},
                            lastHeartbeat: '2026-04-21 10:15:00',
                            locked: true,
                            updateTime: '2026-04-21 10:16:00',
                            fieldSources: {
                                workerId: 'declaration',
                                status: 'runtime',
                                transportReachability: 'transport',
                            },
                        },
                    ],
                    total: 1,
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const response = await listWorkersReal()

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/runtime/workers',
            expect.any(Object),
        )
        expect(response.items[0].fieldSources?.workerId).toBe('declaration')
        expect(response.items[0].fieldSources?.status).toBe('runtime')
    })
})
